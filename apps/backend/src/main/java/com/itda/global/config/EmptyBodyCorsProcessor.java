package com.itda.global.config;

import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.cors.DefaultCorsProcessor;

/**
 * CORS 판정은 Spring 기본 구현 그대로 두고, 거부 응답의 body만 비운다.
 *
 * <p>{@link DefaultCorsProcessor}는 허용되지 않은 Origin·method·header와 형식이 깨진 Origin을 {@code 403}과 {@code
 * Invalid CORS request} 문자열로 끝낸다. 이 거부는 업무 code가 없는 필터 단계의 {@code 403}이므로 상태는 유지하고 body를 비워야 한다
 * (docs/conventions/code/api.md "오류 상태 보존과 body 생략").
 *
 * <p>허용 판정과 허용 응답 헤더는 건드리지 않는다. 바꾸는 것은 {@link #rejectRequest}뿐이다.
 *
 * <p>{@code reset}으로 헤더를 지우지 않는다. 앞선 추적 filter가 붙인 {@code X-Request-Id}와 이미 붙은 {@code Vary}가 그대로
 * 나간다.
 */
class EmptyBodyCorsProcessor extends DefaultCorsProcessor {

  @Override
  protected void rejectRequest(ServerHttpResponse response) throws IOException {
    response.setStatusCode(HttpStatus.FORBIDDEN);
    response.getHeaders().setContentLength(0);
    // 기본 구현과 같이 응답을 확정한다. body를 연 뒤 쓰지 않고 flush해야 servlet 응답이 commit되어, 뒤따르는 ERROR dispatch 처리나
    // 다른 계층이 상태를 바꾸거나 body를 덧붙이지 못한다.
    response.getBody();
    response.flush();
  }
}
