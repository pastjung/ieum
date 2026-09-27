package com.itda;

import java.time.ZoneOffset;
import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ItdaApplication {

  public static void main(String[] args) {
    useUtcAsDefaultTimeZone();
    SpringApplication.run(ItdaApplication.class, args);
  }

  /**
   * JVM 기본 시간대를 UTC 로 고정한다. 반드시 스프링이 뜨기 전에 불러야 한다.
   *
   * <p>DB 설정은 UTC 다({@code hibernate.jdbc.time_zone: UTC}, {@code connectionTimeZone=UTC}). 그런데 JVM
   * 이 한국 시간대(팀원 PC)면 Hibernate 는 {@code LocalDateTime} 을 "한국 시각"으로 보고 9시간을 빼서 저장한다. 그 결과 {@code
   * LocalDateTime.now(UTC)} 로 넣은 값({@code attempted_at}, {@code completed_at}, AI 가 준 {@code
   * last_practiced_at})은 9시간 이르게, {@code @CreationTimestamp}· {@code @UpdateTimestamp} 값은 맞게 저장돼 같은
   * 테이블 안에서 기준이 섞였다. JVM 을 UTC 로 두면 변환이 0 이 되어 전부 같은 기준이 된다.
   */
  static void useUtcAsDefaultTimeZone() {
    TimeZone.setDefault(TimeZone.getTimeZone(ZoneOffset.UTC));
  }
}
