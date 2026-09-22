package com.itda.global.braille;

import com.itda.global.error.BusinessException;
import com.itda.global.error.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 점자 6점 셀의 공용 표현. 프로젝트 전 구간에서 점형은 6비트 정수다 (AGENTS.md).
 *
 * <p>6점 번호는 왼쪽 위부터 아래로 1·2·3, 오른쪽 위부터 아래로 4·5·6이며 bit 0~5가 점 1~6에 대응한다. 따라서 값의 범위는 0~63이고 0은 "아무 점도
 * 선택하지 않은 빈 점형"을 명시적으로 뜻한다 — 누락된 Cell과 구분해야 한다 (ERD 4.4절).
 *
 * <p>특정 바운디드 컨텍스트가 아니라 shared kernel에 두었다. problem 컨텍스트는 정답 패턴으로, learning 컨텍스트는 입력 패턴·차이 마스크로 같은
 * 값을 쓴다.
 */
@Embeddable
@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BraillePattern {

  /** 6점을 모두 채운 값. 유효 범위는 0 이상 이 값 이하다. */
  public static final int MAX_VALUE = 0b111111;

  /** 한 Cell의 점 개수. */
  public static final int DOTS_PER_CELL = 6;

  /**
   * ERD 4.4절의 TINYINT NOT NULL.
   *
   * <p><b>주의</b>: 이 애너테이션은 사용하는 쪽이 @AttributeOverride로 컬럼명을 바꾸면 <b>통째로 교체된다</b>. 그래서 각
   * 사용처의 @AttributeOverride가 nullable·columnDefinition을 다시 명시한다. 여기 값만 고치면 실제 스키마는 바뀌지 않는다.
   */
  @Column(nullable = false, columnDefinition = "TINYINT")
  private int value;

  private BraillePattern(int value) {
    this.value = value;
  }

  public static BraillePattern of(int value) {
    if (value < 0 || value > MAX_VALUE) {
      throw new BusinessException(ErrorCode.PATTERN_OUT_OF_RANGE);
    }
    return new BraillePattern(value);
  }

  /** 아무 점도 선택하지 않은 빈 점형. 미입력이 아니라 "비었다"는 명시적 응답이다. */
  public static BraillePattern empty() {
    return new BraillePattern(0);
  }

  /**
   * 정답과 입력의 차이 마스크. 서버가 expected XOR input으로 계산하며 클라이언트 값을 신뢰하지 않는다 (ERD 5.2절).
   *
   * @return 차이가 있는 점 위치의 비트만 1인 패턴
   */
  public BraillePattern diff(BraillePattern other) {
    return new BraillePattern(this.value ^ other.value);
  }

  /** 정답인 이 패턴에는 있지만 사용자 입력에는 없는 점만 반환한다. */
  public BraillePattern missingIn(BraillePattern input) {
    if (input == null) {
      throw new IllegalArgumentException("입력 패턴은 필수입니다.");
    }
    return new BraillePattern(this.value & ~input.value & MAX_VALUE);
  }

  /** 사용자 입력에는 있지만 정답인 이 패턴에는 없는 점만 반환한다. */
  public BraillePattern extraIn(BraillePattern input) {
    if (input == null) {
      throw new IllegalArgumentException("입력 패턴은 필수입니다.");
    }
    return new BraillePattern(input.value & ~this.value & MAX_VALUE);
  }

  public boolean isEmpty() {
    return value == 0;
  }

  /**
   * 점 번호(1~6)로 점이 켜졌는지 확인한다.
   *
   * @param dotNumber 1 이상 6 이하
   */
  public boolean hasDot(int dotNumber) {
    if (dotNumber < 1 || dotNumber > DOTS_PER_CELL) {
      throw new IllegalArgumentException("점 번호는 1~6이어야 합니다: " + dotNumber);
    }
    return (value & (1 << (dotNumber - 1))) != 0;
  }
}
