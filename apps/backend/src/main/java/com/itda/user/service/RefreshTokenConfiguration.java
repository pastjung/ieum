package com.itda.user.service;

import com.itda.global.security.AccessJwtProperties;
import com.itda.global.security.AccessKeyRing;
import java.time.Clock;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Refresh Token 관련 bean 구성.
 *
 * <p>issuer는 Access 설정에서 그대로 가져온다. 세 종류의 토큰이 같은 발급자를 쓰므로 환경변수를 하나만 유지하고, 같은 값을 두 곳에 적어 한쪽만 바뀌는 사고를
 * 만들지 않는다. 프로필과 issuer의 일치 검증은 {@code AccessJwtConfiguration}이 이미 수행한다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RefreshJwtProperties.class)
public class RefreshTokenConfiguration {

  /**
   * Refresh ring을 만들면서 Access ring과의 {@code kid} 중복을 함께 확인한다.
   *
   * <p>계약(API 명세 3.6.1절)은 {@code kid}가 세 ring을 통틀어 유일해야 한다고 정한다. 겹치면 어느 ring의 key로 검증할지 결정할 수 없고, 한
   * ring의 유출 회전이 다른 ring의 토큰까지 끌고 들어간다.
   *
   * <p>세 ring 전체 유일성은 {@link ActionTokenServiceConfiguration}이 완성한다 — 세 ring을 모두 아는 지점이 거기뿐이다. 여기서는
   * 그중 Access↔Refresh 쌍을 맡는다.
   */
  @Bean
  public RefreshKeyRing refreshKeyRing(
      RefreshJwtProperties properties, AccessKeyRing accessKeyRing) {
    RefreshKeyRing refreshKeyRing = RefreshKeyRing.from(properties);
    requireDistinctKids(accessKeyRing.kids(), refreshKeyRing.kids(), "Access", "Refresh");
    return refreshKeyRing;
  }

  /** kid는 비밀값이 아니지만 어느 kid가 겹쳤는지까지 message에 넣을 이유는 없다. */
  static void requireDistinctKids(
      Set<String> left, Set<String> right, String leftName, String rightName) {
    Set<String> shared = new TreeSet<>(left);
    shared.retainAll(right);
    if (!shared.isEmpty()) {
      throw new IllegalStateException(
          leftName + " ring과 " + rightName + " ring의 kid가 겹칩니다. 서로 다른 kid를 사용해 주세요.");
    }
  }

  @Bean
  public NimbusRefreshTokenCodec nimbusRefreshTokenCodec(
      RefreshKeyRing refreshKeyRing, AccessJwtProperties accessJwtProperties) {
    return new NimbusRefreshTokenCodec(
        refreshKeyRing, accessJwtProperties.issuer(), Clock.systemUTC());
  }
}
