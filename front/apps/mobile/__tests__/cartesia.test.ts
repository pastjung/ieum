import { ApiError, NETWORK_UNAVAILABLE } from "@ieum/api-client";
import { api } from "@/services/apiClient";
import {
  CartesiaError,
  clearSynthesisCache,
  cloneVoice,
  isCartesiaConfigured,
  synthesizeToFile,
} from "@/services/cartesia";
import { VOICE_RATE_LIMITED_MESSAGE, VOICE_UNAVAILABLE_MESSAGE } from "@/services/voiceSynthesisErrors";

/** 가짜 파일 시스템: uri → 내용. 이름이 mock으로 시작해야 jest.mock 팩토리에서 참조할 수 있다 */
const mockFiles = new Map<string, Uint8Array>();

jest.mock("@/services/apiClient", () => ({ api: { get: jest.fn(), post: jest.fn(), put: jest.fn() } }));
jest.mock("expo-file-system", () => {
  const uriOf = (part: string | { uri: string }) => (typeof part === "string" ? part : part.uri);
  class Directory {
    readonly uri: string;
    constructor(base: string | { uri: string }, name: string) {
      this.uri = `${uriOf(base)}/${name}`;
    }
    get exists() {
      return [...mockFiles.keys()].some((key) => key.startsWith(`${this.uri}/`));
    }
    delete() {
      for (const key of [...mockFiles.keys()]) if (key.startsWith(`${this.uri}/`)) mockFiles.delete(key);
    }
  }
  class File {
    readonly uri: string;
    constructor(...parts: Array<string | { uri: string }>) {
      this.uri = parts.map(uriOf).join("/");
    }
    get exists() {
      return mockFiles.has(this.uri);
    }
    get size() {
      return mockFiles.get(this.uri)?.byteLength ?? 0;
    }
    async bytes() {
      return mockFiles.get(this.uri) ?? new Uint8Array();
    }
    create() {
      mockFiles.set(this.uri, new Uint8Array());
    }
    write(data: Uint8Array) {
      mockFiles.set(this.uri, data);
    }
  }
  return { Directory, File, Paths: { cache: "file:///cache" } };
});

const mockedPost = (api as unknown as { post: jest.Mock }).post;
const CLIP = "file:///records/sample.wav";

beforeEach(() => {
  jest.resetAllMocks();
  mockFiles.clear();
});

describe("cloneVoice", () => {
  it("녹음 파일을 multipart(clip, name)로 백엔드에 올리고 voiceId를 받는다", async () => {
    mockFiles.set(CLIP, new Uint8Array([1, 2, 3]));
    mockedPost.mockResolvedValue({ voiceId: "voice-abc" });
    const append = jest.spyOn(FormData.prototype, "append");

    const voice = await cloneVoice(CLIP);

    expect(voice).toEqual({ id: "voice-abc", name: "내 목소리" });
    const [path, body, options] = mockedPost.mock.calls[0] as [string, unknown, { signal?: AbortSignal }];
    expect(path).toBe("/voice/clones");
    expect(body).toBeInstanceOf(FormData);
    expect(options.signal).toBeDefined();
    expect(append).toHaveBeenCalledWith(
      "clip",
      expect.objectContaining({ uri: CLIP, name: "voice-sample.wav", type: "audio/wav" }),
    );
    expect(append).toHaveBeenCalledWith("name", "내 목소리");
    // 언어·설명 같은 Cartesia 전용 필드는 이제 백엔드가 붙인다
    expect(append).toHaveBeenCalledTimes(2);
    append.mockRestore();
  });

  it("녹음 파일이 없으면 서버를 부르지 않는다", async () => {
    await expect(cloneVoice(CLIP)).rejects.toThrow("녹음 파일을 찾지 못했어요");
    expect(mockedPost).not.toHaveBeenCalled();
  });

  it("서버에 키가 없으면(503) 쓸 수 없다는 문구로 실패한다", async () => {
    mockFiles.set(CLIP, new Uint8Array([1]));
    mockedPost.mockRejectedValue(new ApiError(503, "VOICE_SYNTHESIS_UNAVAILABLE", "x"));

    const failure = cloneVoice(CLIP);

    await expect(failure).rejects.toBeInstanceOf(CartesiaError);
    await expect(failure).rejects.toThrow(VOICE_UNAVAILABLE_MESSAGE);
  });

  it("응답에 voiceId가 없으면 실패한다", async () => {
    mockFiles.set(CLIP, new Uint8Array([1]));
    mockedPost.mockResolvedValue({ id: "old-shape" });

    await expect(cloneVoice(CLIP)).rejects.toThrow("목소리를 만들지 못했어요");
  });
});

describe("synthesizeToFile", () => {
  it("백엔드에 {voiceId, text}를 보내 MP3 바이트를 받아 캐시에 쓰고, 같은 문장은 다시 받지 않는다", async () => {
    mockedPost.mockResolvedValue(new Uint8Array([9, 9]).buffer);

    const first = await synthesizeToFile("voice-abc", "네, 잇다예요");
    const second = await synthesizeToFile("voice-abc", "네, 잇다예요");

    expect(second).toBe(first);
    expect(first.startsWith("file:///cache/cartesia-tts/")).toBe(true);
    expect(mockFiles.get(first)).toEqual(new Uint8Array([9, 9]));
    expect(mockedPost).toHaveBeenCalledTimes(1);
    expect(mockedPost).toHaveBeenCalledWith(
      "/voice/speech",
      { voiceId: "voice-abc", text: "네, 잇다예요" },
      expect.objectContaining({ responseType: "arrayBuffer" }),
    );
  });

  it("캐시를 비우면 다시 받는다", async () => {
    mockedPost.mockResolvedValue(new Uint8Array([7]).buffer);

    await synthesizeToFile("voice-abc", "안녕");
    clearSynthesisCache();
    await synthesizeToFile("voice-abc", "안녕");

    expect(mockedPost).toHaveBeenCalledTimes(2);
  });

  it("빈 결과는 실패로 본다", async () => {
    mockedPost.mockResolvedValue(new ArrayBuffer(0));

    await expect(synthesizeToFile("voice-abc", "안녕")).rejects.toThrow("합성 결과가 비어 있어요");
  });

  it("사용량 제한(429)은 1분 뒤 다시 하라는 문구로 실패한다", async () => {
    mockedPost.mockRejectedValue(new ApiError(429, "VOICE_RATE_LIMITED", "x"));

    await expect(synthesizeToFile("voice-abc", "안녕")).rejects.toThrow(VOICE_RATE_LIMITED_MESSAGE);
  });

  it("빈 문장이나 300자를 넘는 문장은 서버를 부르지 않는다 (계약: trim 뒤 1~300자)", async () => {
    await expect(synthesizeToFile("voice-abc", "   ")).rejects.toThrow("1~300자");
    await expect(synthesizeToFile("voice-abc", "가".repeat(301))).rejects.toThrow("1~300자");
    expect(mockedPost).not.toHaveBeenCalled();
  });

  it("25초 안에 응답이 없으면 요청을 끊고 시간 초과를 알린다", async () => {
    jest.useFakeTimers();
    try {
      mockedPost.mockImplementation(
        (_path: string, _body: unknown, options: { signal: AbortSignal }) =>
          new Promise((_resolve, reject) => {
            options.signal.addEventListener("abort", () => reject(new ApiError(0, NETWORK_UNAVAILABLE, "x")));
          }),
      );

      const pending = synthesizeToFile("voice-abc", "안녕");
      jest.advanceTimersByTime(25_000);

      await expect(pending).rejects.toThrow("25초 안에 응답이 오지 않았어요");
    } finally {
      jest.useRealTimers();
    }
  });
});

it("키는 서버에 있으므로 앱은 항상 시도한다", () => {
  expect(isCartesiaConfigured()).toBe(true);
});
