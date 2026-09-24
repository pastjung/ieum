import { AudioModule } from "expo-audio";
import type { AudioStream } from "expo-audio";
import { Directory, File, Paths } from "expo-file-system";
import { buildWav } from "@ieum/itda";

/**
 * 음성 복제에 쓸 샘플을 녹음한다.
 *
 * `AudioStream`으로 **PCM을 직접 받아 WAV로 만든다.** 일반 녹음(`useAudioRecorder`)은
 * 안드로이드에서 m4a/AAC로 나오는데 Cartesia가 그 형식을 받지 않는다.
 *
 * 길게 녹음해도 복제 품질은 좋아지지 않고 업로드만 무거워지므로 15초에서 자동으로 멈춘다.
 */
export const SAMPLE_SAMPLE_RATE = 22_050;
export const SAMPLE_MAX_MS = 15_000;

/** 16bit 모노 기준 약 3초. 이보다 짧으면 복제가 무의미하다. */
const MIN_BYTES = SAMPLE_SAMPLE_RATE * 2 * 3;

export interface VoiceSample {
  readonly uri: string;
  readonly bytes: number;
  readonly seconds: number;
}

export class VoiceSampleRecorder {
  private stream: AudioStream | null = null;
  private chunks: Uint8Array[] = [];
  private actualRate = SAMPLE_SAMPLE_RATE;
  private startedAt = 0;

  get isRecording(): boolean {
    return this.stream !== null;
  }

  get elapsedMs(): number {
    return this.startedAt === 0 ? 0 : Date.now() - this.startedAt;
  }

  /** @returns 실패 사유. 성공이면 null. */
  async start(): Promise<string | null> {
    if (this.stream) return null;

    const permission = await AudioModule.requestRecordingPermissionsAsync();
    if (!permission.granted) return "마이크 권한이 필요해요. 설정에서 허용해 주세요.";

    this.chunks = [];
    this.actualRate = SAMPLE_SAMPLE_RATE;

    try {
      const stream = new AudioModule.AudioStream({
        sampleRate: SAMPLE_SAMPLE_RATE,
        channels: 1,
        encoding: "int16",
      });
      stream.addListener("audioStreamBuffer", (buffer) => {
        // `buffer.data`는 네이티브 direct ByteBuffer를 그대로 비춘 것이라, 뷰만 들고 있으면
        // 그 메모리가 풀린 뒤에 읽게 된다(= 녹음 몇 초 만에 죽는다). 받는 즉시 복사한다.
        this.chunks.push(new Uint8Array(buffer.data.slice(0)));
        this.actualRate = buffer.sampleRate;
      });

      await stream.start();
      this.stream = stream;
      this.startedAt = Date.now();
      return null;
    } catch (error) {
      this.stream = null;
      return `녹음을 시작하지 못했어요: ${error instanceof Error ? error.message : String(error)}`;
    }
  }

  /** 녹음을 끝내고 WAV 파일을 만든다. 너무 짧으면 null. */
  async stop(): Promise<VoiceSample | null> {
    const stream = this.stream;
    if (!stream) return null;

    stream.stop();
    stream.removeAllListeners("audioStreamBuffer");
    this.stream = null;
    this.startedAt = 0;

    const pcmBytes = this.chunks.reduce((total, chunk) => total + chunk.byteLength, 0);
    if (pcmBytes < MIN_BYTES) {
      this.chunks = [];
      return null;
    }

    const wav = buildWav(this.chunks, this.actualRate, 1, 16);
    this.chunks = [];

    const file = new File(new Directory(Paths.cache, "itda-voice"), "voice-sample.wav");
    file.create({ overwrite: true, intermediates: true });
    file.write(wav);

    return {
      uri: file.uri,
      bytes: wav.byteLength,
      seconds: Math.round(pcmBytes / (this.actualRate * 2)),
    };
  }

  cancel(): void {
    this.stream?.stop();
    this.stream?.removeAllListeners("audioStreamBuffer");
    this.stream = null;
    this.chunks = [];
    this.startedAt = 0;
  }
}
