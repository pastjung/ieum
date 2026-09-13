package com.itda.user.infrastructure;

import com.itda.user.application.EmailVerificationRequestService;
import com.itda.user.application.EmailVerificationService;
import com.itda.user.application.MailOutboxEnqueueService;
import com.itda.user.application.port.out.ActionTokenStore;
import com.itda.user.application.port.out.EmailVerificationTokenVerifier;
import com.itda.user.domain.UserRepository;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 이메일 인증 유스케이스의 bean 조립.
 *
 * <p>application 클래스에 Spring 애너테이션을 붙이지 않고 여기서 조립한다 — 유스케이스는 프레임워크 없이도 생성·테스트할 수 있어야 한다.
 *
 * <p>{@code Clock}을 UTC로 고정해 주입한다. 저장하는 시각이 서버 기본 시간대에 좌우되면 안 되고, 테스트가 결정적이어야 한다 (ERD 7절).
 *
 * <p>트랜잭션 경계는 기존 {@link TransactionOperations} bean을 그대로 쓴다. 전파 방식(REQUIRED)과 "execute 밖에서 예외를
 * 분류한다"는 규칙이 회원가입과 같아 경계를 두 벌로 만들 이유가 없다.
 */
@Configuration(proxyBeanMethods = false)
public class EmailVerificationConfiguration {

  @Bean
  public EmailVerificationRequestService emailVerificationRequestService(
      MailOutboxEnqueueService mailOutboxEnqueueService,
      TransactionOperations userRegistrationTransactionOperations) {
    return new EmailVerificationRequestService(
        mailOutboxEnqueueService, userRegistrationTransactionOperations, Clock.systemUTC());
  }

  @Bean
  public EmailVerificationService emailVerificationService(
      EmailVerificationTokenVerifier emailVerificationTokenVerifier,
      ActionTokenStore actionTokenStore,
      UserRepository userRepository,
      TransactionOperations userRegistrationTransactionOperations) {
    return new EmailVerificationService(
        emailVerificationTokenVerifier,
        actionTokenStore,
        userRepository,
        userRegistrationTransactionOperations,
        Clock.systemUTC());
  }
}
