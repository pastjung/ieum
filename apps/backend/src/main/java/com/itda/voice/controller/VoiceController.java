package com.itda.voice.controller;

import com.itda.global.security.AuthenticatedUser;
import com.itda.voice.dto.VoiceCloneResponse;
import com.itda.voice.dto.VoiceSpeechRequest;
import com.itda.voice.service.VoiceSynthesisService;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 목소리 복제·합성 대리 호출 (API 명세 12절).
 *
 * <p>{@code clip} 을 {@code required=false} 로 받는다. 필수로 두면 누락이 {@code
 * MissingServletRequestPartException} 이 되는데 공통 처리기에 그 처리가 없어 500이 된다. 누락·빈 파일·WAV 아님은 서비스가 한꺼번에
 * 400으로 판정한다.
 */
@RestController
@RequiredArgsConstructor
public class VoiceController {

  private static final MediaType AUDIO_MPEG = MediaType.parseMediaType("audio/mpeg");

  private final VoiceSynthesisService voiceSynthesisService;

  @PostMapping(path = "/voice/clones", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<VoiceCloneResponse> cloneVoice(
      @AuthenticationPrincipal AuthenticatedUser user,
      @RequestPart(name = "clip", required = false) MultipartFile clip,
      @RequestParam(name = "name", required = false) String name)
      throws IOException {
    byte[] clipBytes = clip == null ? new byte[0] : clip.getBytes();
    String voiceId = this.voiceSynthesisService.cloneVoice(user, clipBytes, name);
    return ResponseEntity.status(HttpStatus.CREATED).body(new VoiceCloneResponse(voiceId));
  }

  @PostMapping("/voice/speech")
  public ResponseEntity<byte[]> synthesize(
      @AuthenticationPrincipal AuthenticatedUser user, @RequestBody VoiceSpeechRequest request) {
    byte[] audio = this.voiceSynthesisService.synthesize(user, request.voiceId(), request.text());
    return ResponseEntity.ok().contentType(AUDIO_MPEG).body(audio);
  }
}
