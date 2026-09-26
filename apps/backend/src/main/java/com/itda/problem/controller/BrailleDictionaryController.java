package com.itda.problem.controller;

import com.itda.problem.dto.BrailleDictionaryResponse;
import com.itda.problem.service.BrailleDictionaryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 점자 사전 ({@code GET /api/v1/braille-dictionary?word=가방}).
 *
 * <p>개인 정보를 다루지 않으므로 인증을 요구하지 않는다(SecurityConfig 의 보호 목록에 넣지 않음 → permitAll). 로그인 없이도 사전은 쓸 수 있어야
 * 한다.
 */
@RestController
@RequiredArgsConstructor
public class BrailleDictionaryController {

  private final BrailleDictionaryService brailleDictionaryService;

  @GetMapping("/braille-dictionary")
  public ResponseEntity<BrailleDictionaryResponse> lookup(@RequestParam String word) {
    return ResponseEntity.ok(brailleDictionaryService.lookup(word));
  }
}
