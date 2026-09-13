package com.itda.user.application;

import com.itda.global.error.BusinessException;
import com.itda.global.error.ErrorCode;
import com.itda.user.domain.AccountActionPurpose;
import com.itda.user.domain.Email;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 이메일 인증 요청·재발송 (API 명세 3.4·3.4.1절).
 *
 * <p><b>계정을 조회하지 않는다.</b> 이것이 이 클래스의 핵심 제약이다. 계정 존재·{@code status}·{@code email_verified_at}에 따라 동기
 * 경로가 갈라지면 응답 시간이나 오류 종류로 "그 이메일로 가입된 계정이 있는가"가 드러난다. 형식이 유효한 모든 요청은 계정과 무관하게 <b>같은</b> {@code
 * mail_outbox} INSERT 경로를 지난다.
 *
 * <p>그래서 발송 적격 판정(ACTIVE이면서 미인증인 계정에만 보낸다)은 여기가 아니라 비동기 worker의 몫이다. action token 발급도, 메일 제공자 호출도 이
 * 경로에 없다.
 *
 * <p>{@code 202}는 메일 작업이 내구적으로 접수됐다는 뜻뿐이다. 계정 존재, 발송 적격, action token 발급, 제공자 접수, 편지함 도달 중 어느 것도
 * 뜻하지 않는다 (3.7절).
 */
public class EmailVerificationRequestService {

  private final MailOutboxEnqueueService mailOutboxEnqueueService;
  private final TransactionOperations transactionOperations;
  private final Clock clock;

  public EmailVerificationRequestService(
      MailOutboxEnqueueService mailOutboxEnqueueService,
      TransactionOperations transactionOperations,
      Clock clock) {
    this.mailOutboxEnqueueService = mailOutboxEnqueueService;
    this.transactionOperations = transactionOperations;
    this.clock = clock;
  }

  /**
   * 이메일 인증 메일 작업을 접수한다.
   *
   * @param rawEmail 요청 body의 {@code email} 원문. {@code null}과 형식 위반을 여기서 거부한다
   * @throws BusinessException 형식 위반은 {@code INVALID_ACCOUNT_INPUT}, 저장 실패·결과 불확실은 {@code
   *     AUTH_STATE_UNAVAILABLE}
   */
  public void request(String rawEmail) {
    Email email = parseEmail(rawEmail);
    enqueueInTransaction(email);
  }

  private static Email parseEmail(String rawEmail) {
    try {
      return Email.of(rawEmail);
    } catch (IllegalArgumentException e) {
      // 원인 예외를 붙이지 않는다. 이메일 원문이 예외 사슬을 타고 로그로 흘러갈 경로를 만들지 않는다.
      throw new BusinessException(ErrorCode.INVALID_ACCOUNT_INPUT);
    }
  }

  /**
   * INSERT와 commit까지 끝내고 성공이 확정된 경우에만 정상 반환한다 (3.4.1절).
   *
   * <p>회원가입과 같은 이유로 {@code TransactionOperations.execute} <b>밖에서</b> 예외를 분류한다. 트랜잭션 안에서 잡으면 commit은
   * 그 뒤에 일어나므로 commit 실패를 성공으로 돌려주게 된다.
   */
  private void enqueueInTransaction(Email email) {
    LocalDateTime now = LocalDateTime.now(this.clock);
    try {
      this.transactionOperations.executeWithoutResult(
          status ->
              this.mailOutboxEnqueueService.enqueue(
                  AccountActionPurpose.EMAIL_VERIFICATION, email.getValue(), now));
    } catch (DataAccessException | TransactionException e) {
      // 명령 실패·commit 실패·결과 불확실을 하나로 묶는다. 어느 쪽인지 추측해 202로 만들지 않는다 (3.6.2절).
      throw new BusinessException(ErrorCode.AUTH_STATE_UNAVAILABLE);
    }
  }
}
