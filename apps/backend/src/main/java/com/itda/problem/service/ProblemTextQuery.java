package com.itda.problem.service;

import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 강사가 입력한 문제 글자. {@code 초성 ㄱ}처럼 구분어 + 공백 1개 이상 + 글자면 구분어를 떼고, 아니면 전체를 글자로 본다 (API 명세 8.2.1절).
 *
 * @param prefix 구분어가 없으면 null
 */
public record ProblemTextQuery(Prefix prefix, String text) {

  /** 전각 공백도 강사가 흔히 넣는다 — UNICODE_CHARACTER_CLASS로 \s가 그것까지 받게 한다. */
  private static final Pattern PREFIXED =
      Pattern.compile("^(초성|받침|모음|음절)\\s+(.+)$", Pattern.UNICODE_CHARACTER_CLASS);

  /** 한글 호환 자모 ㄱ(U+3131)~ㅣ(U+3163). 자음·모음 문제의 target_text가 이 범위다. */
  private static final char FIRST_JAMO = 'ㄱ';

  private static final char LAST_JAMO = 'ㅣ';

  public static ProblemTextQuery parse(String raw) {
    String trimmed = raw.strip();
    Matcher matcher = PREFIXED.matcher(trimmed);
    if (matcher.matches()) {
      return new ProblemTextQuery(Prefix.fromLabel(matcher.group(1)), matcher.group(2).strip());
    }
    return new ProblemTextQuery(null, trimmed);
  }

  /** 구분어 없는 호환 자모 한 글자 — 자음·모음 후보 중 고르게 한다. */
  public boolean isSingleJamo() {
    return prefix == null
        && text.length() == 1
        && text.charAt(0) >= FIRST_JAMO
        && text.charAt(0) <= LAST_JAMO;
  }

  /** 구분어. label은 입력 문구이자 displayText 앞머리다 — 두 곳이 어긋나지 않게 한 곳에 둔다. */
  public enum Prefix {
    ONSET("초성"),
    CODA("받침"),
    VOWEL("모음"),
    SYLLABLE("음절");

    private final String label;

    Prefix(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    static Prefix fromLabel(String label) {
      return Arrays.stream(values())
          .filter(prefix -> prefix.label.equals(label))
          .findFirst()
          .orElseThrow(() -> new IllegalArgumentException("알 수 없는 구분어: " + label));
    }
  }
}
