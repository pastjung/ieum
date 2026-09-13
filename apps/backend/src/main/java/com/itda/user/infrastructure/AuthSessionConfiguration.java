package com.itda.user.infrastructure;

import com.itda.user.application.DecoyPasswordHash;
import com.itda.user.application.LoginService;
import com.itda.user.application.PasswordVerificationService;
import com.itda.user.application.port.out.AccessTokenIssuer;
import com.itda.user.application.port.out.PasswordHasher;
import com.itda.user.application.port.out.RefreshTokenIssuer;
import com.itda.user.application.port.out.RefreshTokenStore;
import com.itda.user.domain.UserRepository;
import com.itda.user.presentation.ClientPlatformFilter;
import java.time.Clock;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 인증 세션 유스케이스의 bean 조립.
 *
 * <p>application 클래스에 Spring 애너테이션을 붙이지 않고 여기서 조립한다 — 유스케이스는 프레임워크 없이도 생성·테스트할 수 있어야 한다.
 *
 * <p>{@link DecoyPasswordHash}를 bean으로 둔다. 미끼 해시는 프로세스마다 한 번만 만들면 되고, 요청마다 다시 만들면 Argon2id 비용을 두 번
 * 치른다.
 *
 * <p>{@code Clock}을 UTC로 고정한다. 잠금 종료 시각과 갱신 시각이 서버 기본 시간대에 좌우되면 안 된다 (ERD 7절).
 */
@Configuration(proxyBeanMethods = false)
public class AuthSessionConfiguration {

  /**
   * 플랫폼 판정 filter를 로그인 경로에만, Security chain보다 앞 순서로 등록한다.
   *
   * <p>url pattern으로 경로를 좁히므로 filter 안에서 경로를 다시 비교하지 않는다. dispatcher type 기본값(REQUEST)이라 이 filter가
   * 만든 ERROR dispatch에 다시 들어가지도 않는다.
   */
  @Bean
  public FilterRegistrationBean<ClientPlatformFilter> clientPlatformFilterRegistration() {
    FilterRegistrationBean<ClientPlatformFilter> registration =
        new FilterRegistrationBean<>(new ClientPlatformFilter());
    registration.addUrlPatterns(ClientPlatformFilter.LOGIN_PATH);
    registration.setOrder(ClientPlatformFilter.ORDER);
    return registration;
  }

  @Bean
  public DecoyPasswordHash decoyPasswordHash(PasswordHasher passwordHasher) {
    return new DecoyPasswordHash(passwordHasher);
  }

  @Bean
  public LoginService loginService(
      UserRepository userRepository,
      PasswordVerificationService passwordVerificationService,
      DecoyPasswordHash decoyPasswordHash,
      AccessTokenIssuer accessTokenIssuer,
      RefreshTokenIssuer refreshTokenIssuer,
      RefreshTokenStore refreshTokenStore,
      TransactionOperations userRegistrationTransactionOperations) {
    return new LoginService(
        userRepository,
        passwordVerificationService,
        decoyPasswordHash,
        accessTokenIssuer,
        refreshTokenIssuer,
        refreshTokenStore,
        userRegistrationTransactionOperations,
        Clock.systemUTC());
  }
}
