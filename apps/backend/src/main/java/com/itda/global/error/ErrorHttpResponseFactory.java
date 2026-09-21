package com.itda.global.error;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;

/**
 * {@link GlobalExceptionHandler}와 {@link ErrorDispatchController}가 오류 응답을 같은 규칙으로 조립하게 한다.
 *
 * <p>규칙의 원본은 docs/conventions/code/api.md "오류 상태 보존과 body 생략"이다. 오류 상태는 그대로 두고, 요청의 Accept가 JSON을
 * 허용할 때만 {code, message} body를 붙인다. 허용하지 않으면 body만 생략하며 상태를 406으로 바꾸지 않는다.
 *
 * <p>body가 있을 때 Content-Type을 직접 지정하는 이유: Spring의 내용 협상을 다시 타면 협상 실패가 오류 처리 도중에 406이나 /error 재진입으로
 * 번진다. JSON 허용 여부는 여기서 먼저 판정했으므로 협상을 거칠 필요가 없다.
 */
final class ErrorHttpResponseFactory {

  private ErrorHttpResponseFactory() {}

  static ResponseEntity<ErrorResponse> create(HttpServletRequest request, ErrorCode code) {
    return create(request, code, HttpHeaders.EMPTY);
  }

  /**
   * @param headers 오류 상황이 알려 줘야 하는 헤더 (405의 Allow, 415의 Accept). body 생략 여부와 관계없이 보낸다.
   */
  static ResponseEntity<ErrorResponse> create(
      HttpServletRequest request, ErrorCode code, HttpHeaders headers) {
    ResponseEntity.BodyBuilder builder = ResponseEntity.status(code.getStatus()).headers(headers);
    if (!acceptsJson(request)) {
      return builder.build();
    }
    return builder.contentType(MediaType.APPLICATION_JSON).body(ErrorResponse.of(code));
  }

  /** 승인된 code가 없는 오류 상태(403·413·429·503 등)와 406에 쓴다. 상태는 절대 바꾸지 않는다. */
  static ResponseEntity<ErrorResponse> withoutBody(HttpStatusCode status) {
    return ResponseEntity.status(status).build();
  }

  /**
   * Accept가 없거나 값이 비어 있으면 허용으로 본다. 값이 있으면 application/json을 포함하는 range 중 가장 구체적인 것의 q가 0보다 커야 한다 —
   * 그래서 "application/json;q=0, *&#47;*"는 JSON을 명시적으로 거부한 것으로 판정한다. 해석할 수 없는 Accept는 허용하지 않는다.
   */
  static boolean acceptsJson(HttpServletRequest request) {
    List<String> values =
        Collections.list(request.getHeaders(HttpHeaders.ACCEPT)).stream()
            .filter(StringUtils::hasText)
            .toList();
    if (values.isEmpty()) {
      // Accept가 없거나 값이 비어 있으면 Spring의 정상 응답 협상과 같게 */*로 본다.
      return true;
    }
    List<MediaType> ranges;
    try {
      ranges = MediaType.parseMediaTypes(values);
    } catch (InvalidMediaTypeException e) {
      return false;
    }
    return ranges.stream()
        .filter(range -> range.includes(MediaType.APPLICATION_JSON))
        .max(Comparator.comparingInt(ErrorHttpResponseFactory::specificity))
        .map(range -> range.getQualityValue() > 0)
        .orElse(false);
  }

  private static int specificity(MediaType range) {
    return (range.isWildcardType() ? 0 : 2) + (range.isWildcardSubtype() ? 0 : 1);
  }
}
