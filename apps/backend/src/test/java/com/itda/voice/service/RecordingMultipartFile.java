package com.itda.voice.service;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.web.multipart.MultipartFile;

/**
 * 호출 횟수를 세는 {@link MultipartFile} 대역. 역할(403)·키 설정(503)으로 거절된 요청이 실제로 바이트를 읽지 않는지 검증하는 데 쓴다 —
 * getBytes·getInputStream 호출 여부가 곧 "전체 녹음을 메모리에 올렸는가"의 증거다.
 */
public class RecordingMultipartFile implements MultipartFile {

  private final byte[] content;
  public final AtomicInteger getBytesCalls = new AtomicInteger();
  public final AtomicInteger getInputStreamCalls = new AtomicInteger();

  public RecordingMultipartFile(byte[] content) {
    this.content = content;
  }

  @Override
  public String getName() {
    return "clip";
  }

  @Override
  public String getOriginalFilename() {
    return "voice-sample.wav";
  }

  @Override
  public String getContentType() {
    return "audio/wav";
  }

  @Override
  public boolean isEmpty() {
    return this.content.length == 0;
  }

  @Override
  public long getSize() {
    return this.content.length;
  }

  @Override
  public byte[] getBytes() throws IOException {
    this.getBytesCalls.incrementAndGet();
    return this.content;
  }

  @Override
  public InputStream getInputStream() throws IOException {
    this.getInputStreamCalls.incrementAndGet();
    return new ByteArrayInputStream(this.content);
  }

  @Override
  public void transferTo(File dest) throws IOException, IllegalStateException {
    throw new UnsupportedOperationException("테스트에서 쓰지 않는다");
  }
}
