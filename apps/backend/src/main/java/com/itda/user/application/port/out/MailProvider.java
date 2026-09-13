package com.itda.user.application.port.out;

/**
 * 메일 제공자 outbound port (API 명세 3.8절).
 *
 * <p><b>이번 이슈에는 실제 adapter가 없다.</b> 제공자 선택·발신 주소·credential·본문 template은 후속 이슈에서 확정한다. 운영 코드에 발송
 * 성공을 가장하는 no-op 구현을 등록하지 않으며, 자동 테스트는 test source의 stub만 쓴다.
 *
 * <p>구현은 호출 제한 시간(ERD 3.5절: 60초)을 스스로 지키고, 기술 오류를 {@link MailSendResult}의 의미로 분류한다. 제공자가 접수하지 않았다는
 * 사실이 확인되지 않으면 {@link MailSendResult#UNCERTAIN}이다.
 *
 * <p>제공자가 재시도 시각을 명시했다면({@code Retry-After} 등) {@link MailSendOutcome}에 UTC 절대 시각으로 바꿔 담는다. 그 값이
 * 없으면 worker가 내부 backoff를 쓴다.
 *
 * <p>예외를 던지지 않고 결과로 돌려준다. worker가 상태 전이를 결정해야 하는데 예외 종류로 분기하면 제공자마다 다른 예외 계층이 worker로 새어 든다.
 */
public interface MailProvider {

  MailSendOutcome send(MailMessage message);
}
