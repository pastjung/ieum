package com.itda.user.infrastructure;

import com.itda.user.application.MailOutboxEnqueueService;
import com.itda.user.application.NewPasswordService;
import com.itda.user.application.UserRegistrationService;
import com.itda.user.application.UserSummaryQueryService;
import com.itda.user.domain.UserRepository;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 계정 리소스 유스케이스의 bean 구성.
 *
 * <p>application 클래스에 Spring 애너테이션을 붙이지 않고 여기서 조립한다 — 유스케이스는 프레임워크 없이도 생성·테스트할 수 있어야 한다.
 *
 * <p>회원가입 트랜잭션을 {@code @Transactional}이 아니라 {@link TransactionTemplate}으로 여는 이유가 있다. 애너테이션을 쓰면 예외를
 * 잡는 코드가 트랜잭션 proxy <b>안</b>에 있게 되는데, commit은 proxy가 반환할 때 일어나므로 commit 단계에서 드러나는 UNIQUE 충돌을 잡을 수
 * 없다. template은 {@code execute}가 반환되기 전에 commit하므로 호출자가 그 결과를 분류할 수 있다.
 *
 * <p>{@code Clock}을 주입해 생성 시각을 결정적으로 만든다. 시스템 기본 시간대에 의존하지 않도록 UTC로 고정한다 (ERD 7절).
 */
@Configuration(proxyBeanMethods = false)
public class UserAccountConfiguration {

  /**
   * 회원가입 전용 트랜잭션 경계.
   *
   * <p>기본 전파(REQUIRED)를 쓴다. 지금은 호출자가 트랜잭션을 열지 않으므로 새 트랜잭션이 시작되고, {@link MailOutboxEnqueueService}는
   * 이 트랜잭션에 참여한다 — {@code REQUIRES_NEW}를 쓰면 한쪽만 커밋되는 상태가 생긴다 (API 명세 3.2.1절).
   */
  @Bean
  public TransactionOperations userRegistrationTransactionOperations(
      PlatformTransactionManager transactionManager) {
    TransactionTemplate template = new TransactionTemplate(transactionManager);
    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    return template;
  }

  @Bean
  public UserRegistrationService userRegistrationService(
      UserRepository userRepository,
      MailOutboxEnqueueService mailOutboxEnqueueService,
      NewPasswordService newPasswordService,
      TransactionOperations userRegistrationTransactionOperations) {
    return new UserRegistrationService(
        userRepository,
        mailOutboxEnqueueService,
        newPasswordService,
        userRegistrationTransactionOperations,
        Clock.systemUTC());
  }

  @Bean
  public UserSummaryQueryService userSummaryQueryService(UserRepository userRepository) {
    return new UserSummaryQueryService(userRepository);
  }
}
