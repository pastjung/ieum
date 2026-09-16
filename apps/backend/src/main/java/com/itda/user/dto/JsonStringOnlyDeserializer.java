package com.itda.user.dto;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/**
 * JSON 문자열만 문자열로 받는다 (API 명세 3.2.1절).
 *
 * <p>Jackson 기본값은 숫자·불리언을 문자열로 변환해 준다. 그러면 {@code {"username": 1}}이 {@code "1"}로 바인딩되어 타입 불일치가
 * <b>형식 위반(422)</b>으로 둔갑한다. 계약은 본문 타입 불일치를 {@code 400 MALFORMED_REQUEST_BODY}로 정했으므로, 변환하지 않고 읽기를
 * 실패시켜 {@code HttpMessageNotReadableException}으로 나가게 한다.
 *
 * <p>전역 Jackson 설정을 바꾸지 않는다. 이 계약은 계정 API의 것이고, 다른 API의 본문 처리까지 함께 바뀌면 안 된다.
 *
 * <p>JSON {@code null}은 여기까지 오지 않는다 — Jackson이 {@code null}을 그대로 넘겨 주고, 필수 입력 누락 판정은 Service가 한다
 * ({@code 422 INVALID_ACCOUNT_INPUT}).
 */
final class JsonStringOnlyDeserializer extends ValueDeserializer<String> {

  @Override
  public String deserialize(JsonParser parser, DeserializationContext context)
      throws JacksonException {
    if (parser.currentToken() == JsonToken.VALUE_STRING) {
      return parser.getString();
    }
    // 값을 메시지에 넣지 않는다. 어떤 토큰이었는지만으로 원인 파악에 충분하다.
    return context.reportInputMismatch(
        String.class, "문자열이 아닌 값입니다 (token=%s)", parser.currentToken());
  }
}
