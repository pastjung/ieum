package com.itda.user.infrastructure;

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
 * 만들지 않는다. 프로필과 issuer의 일치 검증은 {@link AccessTokenConfiguration}이 이미 수행한다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RefreshJwtProperties.class)
public class RefreshTokenConfiguration {

  /**
   * Refresh ring을 만들면서 Access ring과의 {@code kid} 중복을 함께 확인한다.
   *
   * <p>계약(API 명세 3.6.1절)은 {@code kid}가 세 ring을 통틀어 유일해야 한다고 정한다. 겹치면 어느 ring의 key로 검증할지 결정할 수 없고, 한
   * ring의 유출 회전이 다른 ring의 토큰까지 끌고 들어간다. 두 ring을 모두 아는 지점이 여기뿐이라 여기서 검사한다.
   *
   * <p><b>아직 action ring은 없다.</b> 이번에 보장하는 것은 Access와 Refresh 사이의 유일성뿐이며, 세 ring 전체 유일성은 action
   * ring이 생기는 이슈에서 같은 방식으로 확장해야 한다.
   */
  @Bean
  public RefreshKeyRing refreshKeyRing(
      RefreshJwtProperties properties, AccessKeyRing accessKeyRing) {
    RefreshKeyRing refreshKeyRing = RefreshKeyRing.from(properties);
    Set<String> shared = new TreeSet<>(accessKeyRing.kids());
    shared.retainAll(refreshKeyRing.kids());
    if (!shared.isEmpty()) {
      // kid는 비밀값이 아니지만 어느 kid가 겹쳤는지까지 message에 넣을 이유는 없다.
      throw new IllegalStateException("Access ring과 Refresh ring의 kid가 겹칩니다. 서로 다른 kid를 사용해 주세요.");
    }
    return refreshKeyRing;
  }

  @Bean
  public NimbusRefreshTokenCodec nimbusRefreshTokenCodec(
      RefreshKeyRing refreshKeyRing, AccessJwtProperties accessJwtProperties) {
    return new NimbusRefreshTokenCodec(
        refreshKeyRing, accessJwtProperties.issuer(), Clock.systemUTC());
  }
}
