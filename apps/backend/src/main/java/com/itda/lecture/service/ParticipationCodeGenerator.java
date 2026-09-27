package com.itda.lecture.service;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/** 앞자리 0을 허용하는 숫자 6자리 참여 코드 (ERD 3.2절). 추측을 어렵게 하려고 SecureRandom을 쓴다. */
@Component
public class ParticipationCodeGenerator {

  private static final int CODE_SPACE = 1_000_000;

  private final SecureRandom random = new SecureRandom();

  public String next() {
    return String.format("%06d", random.nextInt(CODE_SPACE));
  }
}
