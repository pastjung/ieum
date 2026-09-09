package com.itda.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.util.Locale;
import java.util.regex.Pattern;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 이메일 Value Object. 값 검증을 도메인 안에서 보장한다 — 잘못된 이메일을 가진 User는 존재할 수 없다.
 *
 * <p>ERD 3.1절대로 앞뒤 공백을 제거하고 소문자로 정규화해 저장한다. 대소문자 비구분 중복 검사는 정규화된 값에 UNIQUE 제약을 걸어 달성한다.
 */
@Embeddable
@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Email {

  /** ERD 3.1절 users.email VARCHAR(254) */
  public static final int MAX_LENGTH = 254;

  private static final Pattern PATTERN = Pattern.compile("^[\\w.%+-]+@[\\w.-]+\\.[A-Za-z]{2,}$");

  @Column(name = "email", nullable = false, unique = true, length = MAX_LENGTH)
  private String value;

  private Email(String value) {
    this.value = value;
  }

  public static Email of(String rawValue) {
    if (rawValue == null) {
      throw new IllegalArgumentException("이메일은 필수입니다.");
    }
    String normalized = rawValue.trim().toLowerCase(Locale.ROOT);
    if (normalized.length() > MAX_LENGTH || !PATTERN.matcher(normalized).matches()) {
      throw new IllegalArgumentException("이메일 형식이 올바르지 않습니다: " + rawValue);
    }
    return new Email(normalized);
  }
}
