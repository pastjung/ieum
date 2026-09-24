import { Directory, File, Paths } from "expo-file-system";

/**
 * Cartesia 음성 복제 API.
 *
 * - `POST /voices/clone`  WAV 샘플을 올리고 voice id를 받는다
 * - `POST /tts/bytes`     그 voice id로 문장을 읽어 MP3를 받는다
 *
 * 규격은 실제 호출로 확인했다 — 인증 `Authorization: Bearer`, 버전 헤더 `Cartesia-Version`,
 * 모델 `sonic-3.6`. 응답은 **MP3로 받는다**: WAV로 받으면 스트리밍용 헤더가 붙어
 * (RIFF 크기 0xFFFFFFFF, data 앞에 LIST 청크) 플레이어가 열지 못한다.
 *
 * API 키는 저장소에 올리지 않는다. `front/apps/mobile/.env`에 넣으면 빌드 시 인라인된다.
 * (`.env.example` 참고. 키가 없으면 음성 복제 기능만 꺼지고 나머지는 그대로 동작한다.)
 */
const API_KEY = process.env.EXPO_PUBLIC_CARTESIA_API_KEY ?? "";
const BASE_URL = "https://api.cartesia.ai";
const API_VERSION = "2026-08-14";
const MODEL_ID = "sonic-3.6";
const LANGUAGE = "ko";

/** 업로드 + 서버 처리까지 기다리는 시간. 클립 크기와 거의 무관하게 12~14초가 걸린다. */
const CLONE_TIMEOUT_MS = 120_000;
const TTS_TIMEOUT_MS = 20_000;

export interface ClonedVoice {
  readonly id: string;
  readonly name: string;
  readonly language: string;
}

export function isCartesiaConfigured(): boolean {
  return API_KEY.length > 0;
}

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
 * RN 구현으로 되돌아가도 되도록 `uri`도 함께 남겨 둔다.
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
export async function cloneVoice(clipUri: string, name = "내 목소리"): Promise<ClonedVoice> {
  requireKey();

  const clip = new File(clipUri);
  if (!clip.exists) throw new CartesiaError(`녹음 파일이 없어요 (${clipUri}).`);

  const form = new FormData();
  form.append("clip", uploadPart(clip, "voice-sample.wav", "audio/wav") as unknown as Blob);
  form.append("name", name);
  form.append("language", LANGUAGE);
  form.append("description", "잇다 사용자 목소리");

  const response = await request("/voices/clone", form, CLONE_TIMEOUT_MS).catch((error: unknown) => {
    throw error instanceof CartesiaError
      ? new CartesiaError(`${error.message} (clip: ${clipUri})`, { cause: error })
      : error;
  });
  const json = (await response.json()) as { id?: string; name?: string; language?: string };
  if (!json.id) throw new CartesiaError("복제 응답에 voice id가 없어요.");

  return { id: json.id, name: json.name ?? name, language: json.language ?? LANGUAGE };
}

/** 복제한 목소리로 문장을 읽어 캐시에 MP3로 저장하고 그 파일 URI를 돌려준다. */
export async function synthesizeToFile(voiceId: string, text: string): Promise<string> {
  requireKey();

  const cached = cacheFile(voiceId, text);
  if (cached.exists) return cached.uri;

  const response = await request("/tts/bytes", ttsRequestBody(voiceId, text), TTS_TIMEOUT_MS);
  const bytes = new Uint8Array(await response.arrayBuffer());
  if (bytes.byteLength === 0) throw new CartesiaError("합성 결과가 비어 있어요.");

  cached.create({ overwrite: true, intermediates: true });
  cached.write(bytes);
  return cached.uri;
}

/**
 * `/tts/bytes` 요청 본문. 순수 함수라 테스트에서 그대로 검증한다.
 * MP3로 받는 이유는 파일 상단 주석 참고.
 */
export function ttsRequestBody(voiceId: string, text: string): string {
  return JSON.stringify({
    model_id: MODEL_ID,
    transcript: text,
    voice: { id: voiceId },
    language: LANGUAGE,
    output_format: { container: "mp3", sample_rate: 44_100, bit_rate: 128_000 },
  });
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

/** 던져진 값을 사람이 읽을 한 줄로. RN의 fetch 실패는 name까지 봐야 구분된다. */
function describe(error: unknown): string {
  if (error instanceof Error) {
    return error.message ? `${error.name}: ${error.message}` : error.name;
  }
  return String(error);
}

function requireKey(): void {
  if (!isCartesiaConfigured()) {
    throw new CartesiaError(
      "Cartesia API 키가 없어요. front/apps/mobile/.env 에 EXPO_PUBLIC_CARTESIA_API_KEY 를 넣고 다시 빌드해 주세요.",
    );
  }
}

async function request(path: string, body: FormData | string, timeoutMs: number): Promise<Response> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);

  try {
    const response = await fetch(`${BASE_URL}${path}`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${API_KEY}`,
        "Cartesia-Version": API_VERSION,
        // FormData일 때는 boundary를 fetch가 직접 붙여야 하므로 Content-Type을 건드리지 않는다.
        ...(typeof body === "string" ? { "Content-Type": "application/json" } : {}),
      },
      body,
      signal: controller.signal,
    });

    if (!response.ok) {
      const detail = await response.text().catch(() => "");
      throw new CartesiaError(`Cartesia ${response.status}: ${detail.slice(0, 300) || "본문 없음"}`);
    }
    return response;
  } catch (error) {
    if (error instanceof CartesiaError) throw error;
    if (error instanceof Error && error.name === "AbortError") {
      throw new CartesiaError(
        `${Math.round(timeoutMs / 1000)}초 안에 응답이 오지 않았어요. 네트워크가 느리면 와이파이에서 다시 시도해 주세요.`,
        { cause: error },
      );
    }
    // 원인을 삼키면 "연결 실패"만 남아 무엇이 잘못됐는지 알 수 없다. 반드시 붙여 보낸다.
    throw new CartesiaError(`Cartesia에 연결하지 못했어요 — ${describe(error)}`, { cause: error });
  } finally {
    clearTimeout(timer);
  }
}
