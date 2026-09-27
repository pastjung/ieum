package com.itda.user.service;

import com.itda.global.config.RuntimeEnvironmentProfile;
import com.itda.user.repository.ActionTokenStore;
import com.itda.user.repository.MailOutboxRepository;
import com.itda.user.repository.UserRepository;
import java.net.URI;
import java.time.Clock;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 메일 발송 작업 관련 bean 구성.
 *
 * <p>접수({@link MailOutboxEnqueueService})는 항상 만든다. 실행(제공자·worker·스케줄러)은 {@code
 * itda.mail.dispatch.schedule.enabled=true}일 때만 {@link DispatchEnabled}가 <b>한 곳에서 함께</b> 만든다. 기본값은
 * 꺼짐이다 — 켜지 않은 환경은 SMTP 자격증명·발신 주소·링크 주소가 없어도 기동하고, 그 대신 메일이 나가지 않는다는 사실을 README가 분명히 적는다(꺼짐 ≠
 * 준비됨).
 *
 * <p>{@code @ConditionalOnBean}으로 "provider가 있으면 worker를 만든다"를 쓰지 않는다. 그 판정은 bean definition 등록 순서에
 * 좌우돼 worker가 <b>조용히 빠진 채</b> 기동이 성공할 수 있다 — 요청 API는 202를 돌려주고 메일만 영영 나가지 않는 상태다. 대신 같은
 * {@code @Configuration} 안에서 worker가 {@link SmtpMailProvider}를 타입으로 요구하므로, provider가 없으면 worker도 없고
 * 그 사실이 기동 실패로 드러난다.
 *
 * <p>{@link MailDispatchProperties}가 {@link MailDispatchPolicy}를 직접 구현하므로 별도 어댑터 bean을 두지 않는다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MailDispatchProperties.class)
public class MailDispatchConfiguration {

  /** 발송 실행 스위치. {@code true}일 때만 제공자·worker·스케줄러를 조립하고 그 설정을 바인딩한다. */
  static final String ENABLED_PROPERTY = "itda.mail.dispatch.schedule.enabled";

  @Bean
  public MailOutboxEnqueueService mailOutboxEnqueueService(
      MailOutboxRepository mailOutboxRepository) {
    return new MailOutboxEnqueueService(mailOutboxRepository);
  }

  /**
   * 활성화 스위치 값은 {@code true}·{@code false}만 받는다.
   *
   * <p>{@code @ConditionalOnProperty(havingValue = "true")}는 {@code yes}·{@code on}·{@code 1}·오타를
   * 전부 "꺼짐"으로 본다. 켜려던 환경이 조용히 꺼진 채 기동하면 요청 API는 202를 돌려주고 메일만 나가지 않는다. 다른 설정 오류가 기동 실패로 드러나는 것과 같은
   * 계약으로 맞춘다.
   */
  @Bean
  public MailDispatchEnabledSwitch mailDispatchEnabledSwitch(Environment environment) {
    return MailDispatchEnabledSwitch.from(environment.getProperty(ENABLED_PROPERTY));
  }

  /** 검증을 통과한 활성화 값. bean으로 두어 기동 단계에 반드시 평가되게 한다. */
  public record MailDispatchEnabledSwitch(boolean enabled) {
    static MailDispatchEnabledSwitch from(String rawValue) {
      if (rawValue == null) {
        return new MailDispatchEnabledSwitch(false);
      }
      if ("true".equalsIgnoreCase(rawValue)) {
        return new MailDispatchEnabledSwitch(true);
      }
      if ("false".equalsIgnoreCase(rawValue)) {
        return new MailDispatchEnabledSwitch(false);
      }
      throw new IllegalStateException(
          ENABLED_PROPERTY + "은(는) true 또는 false여야 합니다. 다른 값은 꺼짐으로 취급하지 않고 기동을 실패시킵니다.");
    }
  }

  /**
   * 발송 실행 조립.
   *
   * <p>제공자·링크·스케줄 설정은 여기서만 바인딩한다. 바깥 클래스에 두면 꺼진 환경에서도 필수값 검증이 돌아 기동이 실패한다.
   *
   * <p>기동 검증용 SMTP 연결 점검이나 시험 메일 발송은 하지 않는다. 다만 스케줄러의 첫 poll은 기동 직후 즉시 돌므로, outbox에 대기 작업이 있으면 기동
   * 직후 실제 발송이 시작된다. 연결·인증 문제는 그 시도의 결과로 outbox 상태와 로그에 드러난다.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(name = ENABLED_PROPERTY, havingValue = "true")
  @EnableConfigurationProperties({
    MailProviderProperties.class,
    MailLinkProperties.class,
    MailDispatchScheduleProperties.class
  })
  static class DispatchEnabled {

    /** {@link MailProviderProperties.Security#PLAINTEXT}를 허용하는 실행 환경. */
    private static final String ISOLATED_TEST_PROFILE = "test";

    /** PLAINTEXT를 허용하는 host. DNS를 묻지 않고 이름으로만 판단한다 — 기동 단계에서 외부 조회를 하지 않기 위해서다. */
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "::1");

    @Bean(destroyMethod = "close")
    public SmtpMailProvider smtpMailProvider(
        MailProviderProperties providerProperties,
        MailLinkProperties linkProperties,
        MailDispatchProperties dispatchProperties,
        Environment environment) {
      String profile = RuntimeEnvironmentProfile.resolve(environment);
      requirePlaintextOnlyInIsolatedTest(providerProperties, profile);
      requireHttpsOutsideDevelopment(linkProperties, profile);
      return new SmtpMailProvider(
          providerProperties,
          linkProperties,
          dispatchProperties.providerTimeout(),
          Clock.systemUTC());
    }

    /**
     * provider를 인터페이스가 아니라 {@link SmtpMailProvider} 타입으로 받는다 — 이 configuration이 만든 provider 없이는
     * worker도 없다.
     */
    @Bean
    public MailDispatchWorker mailDispatchWorker(
        MailOutboxRepository mailOutboxRepository,
        UserRepository userRepository,
        ActionTokenStore actionTokenStore,
        EmailVerificationTokenIssuer emailVerificationTokenIssuer,
        PasswordResetTokenIssuer passwordResetTokenIssuer,
        SmtpMailProvider smtpMailProvider,
        MailDispatchProperties dispatchProperties) {
      return new MailDispatchWorker(
          mailOutboxRepository,
          userRepository,
          actionTokenStore,
          emailVerificationTokenIssuer,
          passwordResetTokenIssuer,
          smtpMailProvider,
          dispatchProperties,
          Clock.systemUTC());
    }

    /** 종료 대기 초과 시 provider 연결 중단을 요청한다. DNS 작업의 실제 종료까지 보장하는 콜백은 아니다. */
    @Bean
    public MailDispatchScheduler mailDispatchScheduler(
        MailDispatchWorker mailDispatchWorker,
        MailDispatchScheduleProperties scheduleProperties,
        SmtpMailProvider smtpMailProvider) {
      return new MailDispatchScheduler(
          mailDispatchWorker,
          scheduleProperties.pollInterval(),
          scheduleProperties.cleanupInterval(),
          scheduleProperties.batchSize(),
          scheduleProperties.shutdownWait(),
          smtpMailProvider::abortInFlight);
    }

    /**
     * 평문·무인증 SMTP는 격리된 테스트 환경의 루프백 테스트 서버에만 허용한다.
     *
     * <p>record 안에서 검사하지 않는 이유: 실행 환경 프로필은 설정값이 아니라 {@link Environment}에서 오고, 그 판정은 {@link
     * RuntimeEnvironmentProfile}이 한 곳에서 맡는다.
     */
    private static void requirePlaintextOnlyInIsolatedTest(
        MailProviderProperties properties, String profile) {
      if (properties.security() != MailProviderProperties.Security.PLAINTEXT) {
        return;
      }
      if (!ISOLATED_TEST_PROFILE.equals(profile)) {
        throw new IllegalStateException(
            "itda.mail.provider.security=PLAINTEXT는 test 실행 환경에서만 허용됩니다. SSL 또는 STARTTLS를 쓰세요.");
      }
      if (!LOOPBACK_HOSTS.contains(properties.host().toLowerCase(java.util.Locale.ROOT))) {
        throw new IllegalStateException(
            "itda.mail.provider.security=PLAINTEXT는 루프백 host(localhost, 127.0.0.1, ::1)에만 허용됩니다.");
      }
    }

    /** URL 형식·루프백 여부는 record가 검증한다. 실행 환경은 공통 resolver의 판정만 사용한다. */
    private static void requireHttpsOutsideDevelopment(
        MailLinkProperties properties, String profile) {
      if ("local".equals(profile) || "test".equals(profile)) {
        return;
      }
      if (!"https".equalsIgnoreCase(URI.create(properties.emailVerificationUrl()).getScheme())
          || !"https".equalsIgnoreCase(URI.create(properties.passwordResetUrl()).getScheme())) {
        throw new IllegalStateException(
            "itda.mail.link의 http URL은 local·test 실행 환경의 루프백 주소에만 허용됩니다. prod에서는 https를 쓰세요.");
      }
    }
  }
}
