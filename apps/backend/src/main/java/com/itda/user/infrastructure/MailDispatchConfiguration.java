package com.itda.user.infrastructure;

import com.itda.user.application.MailDispatchPolicy;
import com.itda.user.application.MailDispatchWorker;
import com.itda.user.application.MailOutboxEnqueueService;
import com.itda.user.application.port.out.MailProvider;
import com.itda.user.domain.MailOutboxRepository;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 메일 발송 작업 관련 bean 구성.
 *
 * <p><b>스케줄러를 켜지 않는다.</b> {@code @EnableScheduling}도 {@code @Scheduled}도 없다. 실제 {@link
 * MailProvider} 구현이 없는 상태에서 worker가 자동으로 돌면 보낼 수 없는 작업의 시도 횟수만 태운다. 운영 코드에 발송 성공을 가장하는 no-op
 * adapter도 등록하지 않는다.
 *
 * <p>{@link MailDispatchProperties}가 {@link MailDispatchPolicy}를 직접 구현하므로 별도 어댑터 bean을 두지 않는다.
 * {@code @ConfigurationProperties} bean 하나가 두 역할을 한다.
 *
 * <p><b>{@link MailDispatchWorker} bean을 여기서 만들지 않는다.</b> {@link MailProvider} 구현이 아직 없어서 만들 수 없는데,
 * {@code @ConditionalOnBean}으로 "있으면 만든다"를 쓰면 판정이 bean definition 등록 순서에 좌우된다. 후속 이슈에서 provider
 * adapter를 추가했는데 그것이 이 클래스보다 늦게 등록되면 worker가 <b>조용히 빠진 채</b> 기동이 성공한다 — 요청 API는 계속 202를 돌려주고 메일만 영영
 * 나가지 않는 상태다. 조건부 등록 대신, provider adapter가 생기는 후속 이슈에서 <b>provider와 worker를 같은
 * {@code @Configuration}에 함께</b> 명시적으로 조립한다. 그러면 provider가 없을 때 worker도 없다는 사실이 타입으로 드러난다.
 *
 * <p>지금 {@link MailDispatchWorker}를 쓰는 곳은 stub provider와 함께 직접 조립하는 테스트뿐이다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MailDispatchProperties.class)
public class MailDispatchConfiguration {

  @Bean
  public MailOutboxEnqueueService mailOutboxEnqueueService(
      MailOutboxRepository mailOutboxRepository) {
    return new MailOutboxEnqueueService(mailOutboxRepository);
  }
}
