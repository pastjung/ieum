package com.itda.user.domain;

/**
 * {@code mail_outbox.last_error_kind}에 저장하는 정제된 내부 분류값.
 *
 * <p><b>enum이 아니라 문자열 상수다.</b> ERD 3.5절이 이 컬럼을 닫힌 열거로 정의하지 않았고, API 명세 3.8절도 같은 이유로 이 값을 지표 label로
 * 쓰지 않는다고 못박았다("ERD 3.5가 그 값을 닫힌 열거로 정의하지 않아 cardinality를 보장할 수 없기 때문"). 영구 DB enum 계약을 여기서 새로 만들면
 * 값을 늘릴 때마다 두 단계 배포가 필요해진다.
 *
 * <p>제공자 원문 오류·예외 message·SQL은 어떤 경우에도 여기에 넣지 않는다.
 */
public final class MailDispatchFailureKinds {

  /** 최대 처리 시도 횟수에 도달해 종결했다. */
  public static final String MAX_ATTEMPTS_EXCEEDED = "MAX_ATTEMPTS_EXCEEDED";

  /** 발송 기한을 넘겨 종결했다. 시도 초과와 동시에 해당하면 이 값을 우선한다 (ERD 3.5절 판정 순서). */
  public static final String DELIVERY_DEADLINE_EXCEEDED = "DELIVERY_DEADLINE_EXCEEDED";

  /** 제공자가 결정적으로 거부했다. */
  public static final String PROVIDER_REJECTED = "PROVIDER_REJECTED";

  /** 제공자가 접수하지 않았음이 확정된 일시적 실패다 ({@code RETRYABLE}). */
  public static final String PROVIDER_RETRYABLE = "PROVIDER_RETRYABLE";

  /** 제공자의 접수 여부를 확정할 수 없다 ({@code UNCERTAIN}). 성공으로도 영구 실패로도 단정하지 않는다. */
  public static final String PROVIDER_UNCERTAIN = "PROVIDER_UNCERTAIN";

  /** 제공자 인증·권한·필수 설정 오류다 ({@code AUTH_CONFIG_ERROR}). 운영자 경고 대상이다. */
  public static final String PROVIDER_AUTH_CONFIG_ERROR = "PROVIDER_AUTH_CONFIG_ERROR";

  /** 계정 작업 토큰 상태 저장소가 응답하지 못해 메일을 보내지 못했다. 제공자 문제가 아니다. */
  public static final String ACTION_TOKEN_STORE_UNAVAILABLE = "ACTION_TOKEN_STORE_UNAVAILABLE";

  /** 계정 작업 토큰 식별자 충돌이 유한 재시도 안에서 풀리지 않았다 (API 명세 3.6.1절). */
  public static final String ACTION_TOKEN_ID_COLLISION = "ACTION_TOKEN_ID_COLLISION";

  private MailDispatchFailureKinds() {}
}
