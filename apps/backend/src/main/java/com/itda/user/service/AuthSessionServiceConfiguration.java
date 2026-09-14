package com.itda.user.service;

import com.itda.user.repository.RefreshTokenStore;
import com.itda.user.repository.UserRepository;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 인증 세션 유스케이스의 bean 조립.
 *
 * <p>Service 클래스에 Spring 애너테이션을 붙이지 않고 여기서 조립한다 — 유스케이스는 프레임워크 없이도 생성·테스트할 수 있어야 한다.
 *
 * <p>{@link DecoyPasswordHash}를 bean으로 둔다. 미끼 해시는 프로세스마다 한 번만 만들면 되고, 요청마다 다시 만들면 Argon2id 비용을 두 번
 * 치른다.
 *
 * <p>{@code Clock}을 UTC로 고정한다. 잠금 종료 시각과 갱신 시각이 서버 기본 시간대에 좌우되면 안 된다 (ERD 7절).
 */
@Configuration(proxyBeanMethods = false)
public class AuthSessionServiceConfiguration {

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

  /**
   * 갱신은 DB를 <b>읽기만</b> 하므로 트랜잭션 경계를 받지 않는다.
   *
   * <p>초기 조회와 회전 후 재확인은 각각 독립된 조회이며, 둘을 한 트랜잭션으로 묶으면 그 사이 다른 트랜잭션이 커밋한 변경을 못 볼 수 있어 재확인이 무의미해진다.
   */
  @Bean
  public AccessTokenRefreshService accessTokenRefreshService(
      RefreshTokenVerifier refreshTokenVerifier,
      RefreshTokenStore refreshTokenStore,
      RefreshTokenIssuer refreshTokenIssuer,
      AccessTokenIssuer accessTokenIssuer,
      UserRepository userRepository) {
    return new AccessTokenRefreshService(
        refreshTokenVerifier,
        refreshTokenStore,
        refreshTokenIssuer,
        accessTokenIssuer,
        userRepository);
  }

  /** 로그아웃은 Redis만 본다. MariaDB는 완료 조건이 아니다 (API 명세 3.6.2절). */
  @Bean
  public AuthSessionRevocationService authSessionRevocationService(
      RefreshTokenVerifier refreshTokenVerifier, RefreshTokenStore refreshTokenStore) {
    return new AuthSessionRevocationService(refreshTokenVerifier, refreshTokenStore);
  }
}
