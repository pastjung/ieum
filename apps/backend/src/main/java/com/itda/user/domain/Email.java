package com.itda.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.util.regex.Pattern;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 이메일 Value Object. 값 검증을 도메인 안에서 보장한다 — 잘못된 이메일을 가진 User는 존재할 수 없다. */
@Embeddable
@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Email {

  private static final Pattern PATTERN = Pattern.compile("^[\\w.%+-]+@[\\w.-]+\\.[A-Za-z]{2,}$");

  @Column(name = "email", nullable = false, unique = true, length = 100)
  private String value;

  private Email(String value) {
    this.value = value;
  }

  public static Email of(String value) {
    if (value == null || !PATTERN.matcher(value).matches()) {
      throw new IllegalArgumentException("이메일 형식이 올바르지 않습니다: " + value);
    }
    return new Email(value);
  }
}
