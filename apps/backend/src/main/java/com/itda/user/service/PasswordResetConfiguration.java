package com.itda.user.service;

import com.itda.user.repository.ActionTokenStore;
import com.itda.user.repository.RefreshTokenStore;
import com.itda.user.repository.UserRepository;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 비밀번호 재설정 유스케이스의 bean 조립.
 *
 * <p>Service 클래스에 Spring 애너테이션을 붙이지 않고 여기서 조립한다 — 유스케이스는 프레임워크 없이도 생성·테스트할 수 있어야 한다.
 *
 * <p>검증기·Redis 저장소·비밀번호 정책·트랜잭션 경계는 이미 등록된 bean을 그대로 쓴다. 트랜잭션 경계는 이메일 인증과 같은 {@code
 * userRegistrationTransactionOperations}다 — 전파 방식(REQUIRED)과 "execute 밖에서 예외를 분류한다"는 규칙이 같아 두 벌로 만들
 * 이유가 없다. {@code Clock}은 UTC로 고정한다 (ERD 7절).
 */
@Configuration(proxyBeanMethods = false)
public class PasswordResetConfiguration {

  @Bean
  public PasswordResetRequestService passwordResetRequestService(
      MailOutboxEnqueueService mailOutboxEnqueueService,
      TransactionOperations userRegistrationTransactionOperations) {
    return new PasswordResetRequestService(
        mailOutboxEnqueueService, userRegistrationTransactionOperations, Clock.systemUTC());
  }

  @Bean
  public PasswordResetService passwordResetService(
      PasswordResetTokenVerifier passwordResetTokenVerifier,
      ActionTokenStore actionTokenStore,
      UserRepository userRepository,
      NewPasswordService newPasswordService,
      RefreshTokenStore refreshTokenStore,
      TransactionOperations userRegistrationTransactionOperations) {
    return new PasswordResetService(
        passwordResetTokenVerifier,
        actionTokenStore,
        userRepository,
        newPasswordService,
        refreshTokenStore,
        userRegistrationTransactionOperations,
        Clock.systemUTC());
  }
}
