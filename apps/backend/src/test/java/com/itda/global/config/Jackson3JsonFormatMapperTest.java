package com.itda.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.itda.learning.domain.AssignedProblem;
import java.util.List;
import org.hibernate.type.descriptor.java.JavaType;
import org.hibernate.type.descriptor.java.spi.JavaTypeBasicAdaptor;
import org.junit.jupiter.api.Test;

/**
 * Hibernate가 JSON 컬럼을 읽고 쓸 때 쓰는 어댑터의 계약을 고정한다.
 *
 * <p>Jackson 3의 예외는 unchecked라 처리가 강제되지 않지만, 매핑 실패는 대상 Type을 담은 IllegalArgumentException으로 드러나야 한다
 * — Hibernate가 번들한 Jackson 2판 구현과 같은 동작이다.
 */
class Jackson3JsonFormatMapperTest {

  private static final JavaType<List> LIST_TYPE = new JavaTypeBasicAdaptor<>(List.class);

  private final Jackson3JsonFormatMapper mapper = new Jackson3JsonFormatMapper();

  @Test
  void 값을_JSON으로_썼다가_그대로_되읽는다() {
    List<AssignedProblem> value = List.of(AssignedProblem.of(1, 101L));

    String json = mapper.toString(value, LIST_TYPE, null);
    List<?> restored = mapper.fromString(json, LIST_TYPE, null);

    assertThat(json).contains("101");
    assertThat(restored).hasSize(1);
  }

  @Test
  void 깨진_JSON은_대상_타입을_담은_예외로_실패한다() {
    assertThatThrownBy(() -> mapper.fromString("{not json", LIST_TYPE, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("java.util.List");
  }
}
