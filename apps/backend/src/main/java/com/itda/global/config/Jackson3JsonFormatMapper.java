package com.itda.global.config;

import java.lang.reflect.Type;
import org.hibernate.type.format.AbstractJsonFormatMapper;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * JSON 컬럼(@JdbcTypeCode(SqlTypes.JSON))을 Jackson 3으로 직렬화하는 Hibernate FormatMapper.
 *
 * <p><b>왜 직접 만드는가</b>: Spring Boot 4는 Jackson 3(<code>tools.jackson</code>)을 쓰지만, Hibernate 7.2가
 * 번들한 JacksonJsonFormatMapper는 Jackson 2(<code>com.fasterxml.jackson</code>)를 찾는다. 그래서 자동 탐지가 실패하고
 * "Could not find a FormatMapper for the JSON format"으로 끝난다. Jackson 2를 추가로 끌어오면 한 프로젝트에 두 Jackson이
 * 공존하므로, 대신 이미 있는 Jackson 3에 어댑터를 붙인다.
 *
 * <p><b>예외를 감싸는 이유</b>: Jackson 3의 JacksonException은 RuntimeException이라 처리가 강제되지 않지만, 상위
 * AbstractJsonFormatMapper는 예외를 감싸지 않고 Hibernate가 번들한 Jackson 2판 구현이 직접 IllegalArgumentException으로
 * 감싼다. 그 구현과 동일하게 대상 Type을 메시지에 남겨야 매핑 실패 원인을 추적할 수 있다. 직렬화도 값의 런타임 타입이 아니라 Hibernate가 넘긴 선언 Type을
 * 쓴다.
 *
 * <p>Hibernate가 <code>hibernate.type.json_format_mapper</code> 설정값으로 리플렉션 생성하므로 기본 생성자가 필요하다.
 * Spring 컨텍스트의 ObjectMapper를 주입받을 수 없으니 전용 인스턴스를 만든다 — 도메인 JSON 직렬화가 웹 계층 설정 변경에 휘둘리지 않는 편이 낫다.
 */
public class Jackson3JsonFormatMapper extends AbstractJsonFormatMapper {

  private final ObjectMapper objectMapper;

  public Jackson3JsonFormatMapper() {
    this.objectMapper = JsonMapper.builder().build();
  }

  @Override
  protected <T> T fromString(CharSequence charSequence, Type type) {
    try {
      return objectMapper.readValue(charSequence.toString(), objectMapper.constructType(type));
    } catch (JacksonException e) {
      throw new IllegalArgumentException("JSON을 자바 타입으로 변환하지 못했습니다: " + type, e);
    }
  }

  @Override
  protected <T> String toString(T value, Type type) {
    try {
      return objectMapper.writerFor(objectMapper.constructType(type)).writeValueAsString(value);
    } catch (JacksonException e) {
      throw new IllegalArgumentException("자바 타입을 JSON으로 변환하지 못했습니다: " + type, e);
    }
  }
}
