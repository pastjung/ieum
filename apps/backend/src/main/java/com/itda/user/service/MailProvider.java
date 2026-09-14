package com.itda.user.service;

import com.itda.user.domain.AccountActionPurpose;
import java.time.Instant;
import java.util.Optional;

/**
 * 메일 제공자 outbound port (API 명세 3.8절).
 *
 * <p><b>이번 이슈에는 실제 adapter가 없다.</b> 제공자 선택·발신 주소·credential·본문 template은 후속 이슈에서 확정한다. 운영 코드에 발송
 * 성공을 가장하는 no-op 구현을 등록하지 않으며, 자동 테스트는 test source의 stub만 쓴다.
 *
 * <p>구현은 호출 제한 시간(ERD 3.5절: 60초)을 스스로 지키고, 기술 오류를 {@link SendResult}의 의미로 분류한다. 제공자가 접수하지 않았다는 사실이
 * 확인되지 않으면 {@link SendResult#UNCERTAIN}이다.
 *
 * <p>제공자가 재시도 시각을 명시했다면({@code Retry-After} 등) {@link SendOutcome}에 UTC 절대 시각으로 바꿔 담는다. 그 값이 없으면
 * worker가 내부 backoff를 쓴다.
 *
 * <p>예외를 던지지 않고 결과로 돌려준다. worker가 상태 전이를 결정해야 하는데 예외 종류로 분기하면 제공자마다 다른 예외 계층이 worker로 새어 든다.
 */
public interface MailProvider {

  SendOutcome send(Message message);

  /**
   * 제공자에게 넘길 메일 한 통.
   *
   * <p>제목·본문·링크를 담지 않는다. 그 값들은 아직 확정되지 않았고(발신 주소·환경별 base URL·template), 확정되면 제공자 adapter가 이 값으로
   * 렌더링한다. 여기서는 "누구에게, 어떤 용도로, 어떤 토큰을" 보내는지만 전달한다.
   *
   * <p>{@code actionToken}은 원문이다. 저장·로그 대상이 아니며 {@code toString()}에도 나오지 않는다.
   */
  record Message(AccountActionPurpose purpose, String recipientEmail, String actionToken) {

    public Message {
      if (purpose == null) {
        throw new IllegalArgumentException("발송 용도가 없습니다.");
      }
      if (recipientEmail == null || recipientEmail.isBlank()) {
        throw new IllegalArgumentException("수신 주소가 없습니다.");
      }
      if (actionToken == null || actionToken.isBlank()) {
        throw new IllegalArgumentException("계정 작업 토큰이 없습니다.");
      }
    }

    /** 수신 주소와 토큰 원문을 로그로 내보내지 않는다 (ERD 3.5절 로그 규칙). 출력 문자열은 통합 전 top-level 타입 이름을 유지한다. */
    @Override
    public String toString() {
      return "MailMessage[purpose=" + this.purpose + ", REDACTED]";
    }
  }

  /**
   * 제공자 호출 한 번의 결과.
   *
   * <p>결과 종류는 {@link SendResult} 다섯 개로 닫혀 있고(API 명세 3.8절), 여기에 <b>제공자가 지시한 재시도 시각</b>만 선택적으로 덧붙인다.
   * ERD 3.5절은 "제공자가 지시한 재시도 시각은 기한 안이면 존중하며, 내부 지수 backoff의 상한은 그 지시를 더 이른 시각으로 앞당기는 근거로 쓰지 않는다"고
   * 정하는데, enum 하나만으로는 그 시각을 worker에게 전달할 방법이 없다.
   *
   * <p><b>제공자 원문 오류·응답 body·예외 message를 담지 않는다.</b> 담을 자리를 만들지 않는 것이 규칙을 지키는 가장 확실한 방법이다 (API 명세
   * 3.8절 금지 label). adapter는 자기 기술 오류를 다섯 값의 의미로 분류하고, 재시도 시각은 제공자가 명시한 경우에만 채운다.
   *
   * <p>시각은 절대 시각({@link Instant})이다. 제공자 응답의 상대 초나 지역 시각을 그대로 옮기지 않고 adapter가 UTC 절대 시각으로 바꿔서 넘긴다.
   */
  record SendOutcome(SendResult result, Instant providerRetryAt) {

    public SendOutcome {
      if (result == null) {
        throw new IllegalArgumentException("제공자 호출 결과가 없습니다.");
      }
      if (providerRetryAt != null && !result.isRetryable()) {
        // 재시도하지 않는 결과에 재시도 시각이 오면 둘 중 하나는 잘못된 분류다. 조용히 버리지 않고 거부한다.
        throw new IllegalArgumentException("재시도하지 않는 결과에는 재시도 시각을 넣을 수 없습니다.");
      }
    }

    /** 제공자가 재시도 시각을 지시하지 않은 결과. */
    public static SendOutcome of(SendResult result) {
      return new SendOutcome(result, null);
    }

    /** 제공자가 재시도 시각을 지시한 결과. 재시도 가능한 결과에만 쓸 수 있다. */
    public static SendOutcome retryAt(SendResult result, Instant providerRetryAt) {
      if (providerRetryAt == null) {
        throw new IllegalArgumentException("제공자가 지시한 재시도 시각이 없습니다.");
      }
      return new SendOutcome(result, providerRetryAt);
    }

    public Optional<Instant> retryAt() {
      return Optional.ofNullable(this.providerRetryAt);
    }
  }

  /**
   * 메일 제공자 호출 결과 (API 명세 3.8절 「제공자 호출 결과의 구분」).
   *
   * <p>다섯 값은 서로 배타적이며 판정 기준은 <b>제공자의 접수 여부</b>다. 각 제공자 adapter가 자기 기술 오류를 이 의미로 분류하며 제공자 원문 오류를 값으로
   * 쓰지 않는다.
   */
  enum SendResult {

    /** 제공자가 요청 접수를 명시적으로 확인했다. 편지함 도달을 뜻하지 않는다. */
    ACCEPTED,

    /** 결정적 영구 거부다. 재시도하지 않는다. */
    REJECTED,

    /** 인증·권한·필수 설정 오류다. 기한과 최대 시도 범위 안에서 재시도하되 운영자에게 알린다. */
    AUTH_CONFIG_ERROR,

    /** 제공자가 접수하지 않았음이 확정된 일시적 실패다. 다시 시도할 수 있다. */
    RETRYABLE,

    /**
     * 요청을 보낸 뒤 시간 초과나 연결 단절 등으로 접수 여부를 확정할 수 없다.
     *
     * <p>단순한 시간 초과를 무조건 {@link #RETRYABLE}로 분류하지 않는다. 제공자가 접수하지 않았다는 사실이 확인되지 않으면 이 값이다.
     */
    UNCERTAIN;

    /**
     * 기한과 최대 시도 범위 안에서 다시 시도하는 결과인지.
     *
     * <p>{@link #ACCEPTED}는 끝난 결과고 {@link #REJECTED}는 결정적 영구 거부라 둘 다 다시 시도하지 않는다. 나머지 셋은 ERD 3.5절의
     * 재시도 경로로 간다. 이 구분이 {@link SendOutcome}에서 제공자 지정 재시도 시각을 받을 수 있는 결과를 결정한다.
     */
    public boolean isRetryable() {
      return this == AUTH_CONFIG_ERROR || this == RETRYABLE || this == UNCERTAIN;
    }
  }
}
