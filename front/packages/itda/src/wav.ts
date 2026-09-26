/**
 * 44바이트 WAV(RIFF) 헤더.
 *
 * 마이크에서 받는 건 헤더 없는 PCM인데, Cartesia 음성 복제는 WAV를 받는다.
 * (m4a/AAC는 받지 않는다 — 그래서 녹음을 PCM으로 받아 여기서 WAV로 만든다.)
 */
export const WAV_HEADER_SIZE = 44;

export function wavHeader(
  pcmSize: number,
  sampleRate: number,
  channels = 1,
  bitsPerSample = 16,
): Uint8Array {
  if (pcmSize < 0) throw new Error(`pcmSize는 음수일 수 없다: ${pcmSize}`);

  const byteRate = (sampleRate * channels * bitsPerSample) / 8;
  const blockAlign = (channels * bitsPerSample) / 8;
  const header = new Uint8Array(WAV_HEADER_SIZE);
  const view = new DataView(header.buffer);
  let offset = 0;

  const ascii = (text: string) => {
    for (const char of text) header[offset++] = char.charCodeAt(0);
  };
  const int32 = (value: number) => {
    view.setUint32(offset, value, true); // WAV는 리틀 엔디언
    offset += 4;
  };
  const int16 = (value: number) => {
    view.setUint16(offset, value, true);
    offset += 2;
  };

  ascii("RIFF");
  int32(36 + pcmSize);
  ascii("WAVE");

  ascii("fmt ");
  int32(16); // PCM 포맷 청크 길이
  int16(1); // 1 = PCM (압축 없음)
  int16(channels);
  int32(sampleRate);
  int32(byteRate);
  int16(blockAlign);
  int16(bitsPerSample);

  ascii("data");
  int32(pcmSize);

  return header;
}

/** PCM 조각들을 이어 붙이고 앞에 헤더를 달아 완성된 WAV 바이트를 만든다. */
export function buildWav(
  chunks: readonly Uint8Array[],
  sampleRate: number,
  channels = 1,
  bitsPerSample = 16,
): Uint8Array {
  const pcmSize = chunks.reduce((total, chunk) => total + chunk.byteLength, 0);
  const wav = new Uint8Array(WAV_HEADER_SIZE + pcmSize);
  wav.set(wavHeader(pcmSize, sampleRate, channels, bitsPerSample), 0);

  let offset = WAV_HEADER_SIZE;
  for (const chunk of chunks) {
    wav.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return wav;
}
