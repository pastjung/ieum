import { Directory, File, Paths } from "expo-file-system";
import { z } from "zod";
import { api } from "./apiClient";
import { voiceFailureMessage } from "./voiceSynthesisErrors";

/**
 * 내 목소리 복제·합성 — **백엔드 대리 호출**을 쓴다 (설계 2026-09-27-deploy-rebuild-design.md §6).
 *
 * - `POST /voice/clones`  WAV 샘플(multipart `clip`, `name`)을 올리고 `{voiceId}`를 받는다
 * - `POST /voice/speech`  `{voiceId, text}`로 문장을 읽어 MP3 바이트를 받는다
 *
 * Cartesia 키·모델·출력 형식은 backend에만 있다. APK에는 키가 들어가지 않는다.
 * 서버에 키가 없으면 503, 너무 자주 부르면 429, Cartesia가 실패하면 502 — 사용자 문구는
 * `voiceSynthesisErrors`가 정한다. 합성 결과는 기기 캐시(`cartesia-tts`)에 남겨 같은 문장은 다시 받지 않는다.
 */

/** 업로드 + 서버의 Cartesia 대기(60초). 서버가 먼저 끊고 502를 주도록 조금 길게 둔다 */
const CLONE_TIMEOUT_MS = 90_000;
/** 서버의 합성 대기(20초)보다 조금 길게. 이보다 길면 잇다 안내가 너무 늦게 기본 목소리로 넘어간다 */
const TTS_TIMEOUT_MS = 25_000;
/** 서버 계약: text는 앞뒤 공백을 뺀 뒤 1~300자 */
const MAX_TEXT_LENGTH = 300;
const DEFAULT_VOICE_NAME = "내 목소리";

const cloneResponseSchema = z.object({ voiceId: z.string().min(1) });

export interface ClonedVoice {
  readonly id: string;
  readonly name: string;
}

/** 키는 서버에 있으므로 앱은 항상 시도한다. 쓸 수 없으면 서버가 503으로 알린다 */
export function isCartesiaConfigured(): boolean {
  return true;
}

/** 사용자에게 그대로 보여 주고 읽어 줄 수 있는 문장을 message로 갖는다 */
export class CartesiaError extends Error {
  constructor(message: string, options?: { cause?: unknown }) {
    super(message, options);
    this.name = "CartesiaError";
  }
}

/**
 * 멀티파트에 실을 파일 한 조각.
 *
 * Expo가 전역 `fetch`를 자기 구현으로 덮어 두는데, **그쪽은 RN식 `{uri}` 파트를 읽지 않는다**
 * (`Unsupported FormDataPart implementation`). 받아 주는 건 `bytes()`를 가진 객체뿐이다.
 * RN 구현으로 되돌아가도 되도록 `uri`도 함께 남겨 둔다. api-client도 같은 전역 fetch를 쓴다.
 */
function uploadPart(file: File, name: string, type: string) {
  return {
    uri: file.uri,
    name,
    type,
    size: file.size,
    bytes: () => file.bytes(),
  };
}

/** 음성 샘플(WAV) 하나로 목소리를 복제한다. */
export async function cloneVoice(clipUri: string, name = DEFAULT_VOICE_NAME): Promise<ClonedVoice> {
  const clip = new File(clipUri);
  if (!clip.exists) throw new CartesiaError("녹음 파일을 찾지 못했어요. 다시 녹음해 주세요.");

  const form = new FormData();
  form.append("clip", uploadPart(clip, "voice-sample.wav", "audio/wav") as unknown as Blob);
  form.append("name", name);

  const response = await withTimeout(CLONE_TIMEOUT_MS, (signal) =>
    api.post<unknown>("/voice/clones", form, { signal }),
  );
  const parsed = cloneResponseSchema.safeParse(response);
  if (!parsed.success) throw new CartesiaError("목소리를 만들지 못했어요. 잠시 뒤 다시 시도해 주세요.");

  return { id: parsed.data.voiceId, name };
}

/** 복제한 목소리로 문장을 읽어 캐시에 MP3로 저장하고 그 파일 URI를 돌려준다. */
export async function synthesizeToFile(voiceId: string, text: string): Promise<string> {
  const length = text.trim().length;
  if (length === 0 || length > MAX_TEXT_LENGTH) {
    throw new CartesiaError(`합성할 수 있는 문장은 1~${MAX_TEXT_LENGTH}자예요.`);
  }

  const cached = cacheFile(voiceId, text);
  if (cached.exists) return cached.uri;

  const buffer = await withTimeout(TTS_TIMEOUT_MS, (signal) =>
    api.post<ArrayBuffer>("/voice/speech", { voiceId, text }, { signal, responseType: "arrayBuffer" }),
  );
  const bytes = new Uint8Array(buffer);
  if (bytes.byteLength === 0) throw new CartesiaError("합성 결과가 비어 있어요.");

  cached.create({ overwrite: true, intermediates: true });
  cached.write(bytes);
  return cached.uri;
}

// --- 캐시 -------------------------------------------------------------------

const CACHE_DIR = "cartesia-tts";

/** 같은 문장은 한 번만 받는다. "네, 잇다예요" 같은 고정 응답이 매번 네트워크를 타지 않게. */
function cacheFile(voiceId: string, text: string): File {
  return new File(new Directory(Paths.cache, CACHE_DIR), `${voiceId.slice(0, 12)}_${hash(text)}.mp3`);
}

/** 파일 이름에 쓸 짧은 해시. 암호학적 용도가 아니라 충돌만 피하면 된다(djb2). */
function hash(text: string): string {
  let value = 5381;
  for (let i = 0; i < text.length; i += 1) {
    value = ((value << 5) + value + text.charCodeAt(i)) | 0;
  }
  return (value >>> 0).toString(16);
}

/** 복제한 목소리를 지울 때 캐시도 함께 비운다 — 옛 목소리가 남아 있으면 안 된다. */
export function clearSynthesisCache(): void {
  const directory = new Directory(Paths.cache, CACHE_DIR);
  if (directory.exists) directory.delete();
}

// --- 공통 -------------------------------------------------------------------

/**
 * 시간 제한을 걸어 부르고, 실패를 사용자 문장으로 바꾼다.
 * 취소된 요청은 api-client에서 NETWORK_UNAVAILABLE로 오므로 신호 상태로 시간 초과를 구분한다.
 */
async function withTimeout<T>(timeoutMs: number, run: (signal: AbortSignal) => Promise<T>): Promise<T> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    return await run(controller.signal);
  } catch (error) {
    if (controller.signal.aborted) {
      throw new CartesiaError(
        `${Math.round(timeoutMs / 1000)}초 안에 응답이 오지 않았어요. 네트워크가 느리면 와이파이에서 다시 시도해 주세요.`,
        { cause: error },
      );
    }
    throw new CartesiaError(voiceFailureMessage(error), { cause: error });
  } finally {
    clearTimeout(timer);
  }
}
