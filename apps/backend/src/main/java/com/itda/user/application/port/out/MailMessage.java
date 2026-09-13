package com.itda.user.application.port.out;

import com.itda.user.domain.AccountActionPurpose;

/**
 * 제공자에게 넘길 메일 한 통.
 *
 * <p>제목·본문·링크를 담지 않는다. 그 값들은 아직 확정되지 않았고(발신 주소·환경별 base URL·template), 확정되면 제공자 adapter가 이 값으로
 * 렌더링한다. 여기서는 "누구에게, 어떤 용도로, 어떤 토큰을" 보내는지만 전달한다.
 *
 * <p>{@code actionToken}은 원문이다. 저장·로그 대상이 아니며 {@code toString()}에도 나오지 않는다.
 */
public record MailMessage(AccountActionPurpose purpose, String recipientEmail, String actionToken) {

  public MailMessage {
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

  /** 수신 주소와 토큰 원문을 로그로 내보내지 않는다 (ERD 3.5절 로그 규칙). */
  @Override
  public String toString() {
    return "MailMessage[purpose=" + this.purpose + ", REDACTED]";
  }
}
