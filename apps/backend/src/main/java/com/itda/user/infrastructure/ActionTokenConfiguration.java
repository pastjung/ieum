package com.itda.user.infrastructure;

import com.itda.user.application.port.out.ActionTokenStore;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 계정 작업 토큰 관련 bean 구성.
 *
 * <p>issuer는 Access 설정에서 그대로 가져온다. 세 종류의 토큰이 같은 발급자를 쓰므로 환경변수를 하나만 유지하고, 같은 값을 여러 곳에 적어 한쪽만 바뀌는 사고를
 * 만들지 않는다. 프로필과 issuer의 일치 검증은 {@link AccessTokenConfiguration}이 이미 수행한다.
 *
 * <p>이메일 인증과 비밀번호 재설정은 ring 하나를 공유하되 codec bean과 port는 따로 등록한다. 주입 지점에서 종류가 갈리므로 잘못된 용도의 발급기를 받는
 * 실수가 컴파일 단계에서 드러난다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ActionJwtProperties.class)
public class ActionTokenConfiguration {

  /**
   * action ring을 만들면서 <b>세 ring 전체의 {@code kid} 유일성</b>을 완성한다.
   *
   * <p>계약(API 명세 3.6.1절)은 "{@code kid}는 세 ring을 통틀어 유일해야 한다"고 정한다. Access↔Refresh는 {@link
   * RefreshTokenConfiguration}이 이미 확인하므로, 여기서 action↔Access와 action↔Refresh를 확인하면 세 쌍이 모두 닫힌다. 세
   * ring을 한 자리에서 볼 수 있는 지점이 여기뿐이다.
   */
  @Bean
  public ActionKeyRing actionKeyRing(
      ActionJwtProperties properties, AccessKeyRing accessKeyRing, RefreshKeyRing refreshKeyRing) {
    ActionKeyRing actionKeyRing = ActionKeyRing.from(properties);
    RefreshTokenConfiguration.requireDistinctKids(
        accessKeyRing.kids(), actionKeyRing.kids(), "Access", "action");
    RefreshTokenConfiguration.requireDistinctKids(
        refreshKeyRing.kids(), actionKeyRing.kids(), "Refresh", "action");
    return actionKeyRing;
  }

  @Bean
  public NimbusEmailVerificationTokenCodec nimbusEmailVerificationTokenCodec(
      ActionKeyRing actionKeyRing, AccessJwtProperties accessJwtProperties) {
    return new NimbusEmailVerificationTokenCodec(
        actionKeyRing, accessJwtProperties.issuer(), Clock.systemUTC());
  }

  @Bean
  public NimbusPasswordResetTokenCodec nimbusPasswordResetTokenCodec(
      ActionKeyRing actionKeyRing, AccessJwtProperties accessJwtProperties) {
    return new NimbusPasswordResetTokenCodec(
        actionKeyRing, accessJwtProperties.issuer(), Clock.systemUTC());
  }

  @Bean
  public RedisScript<String> issueActionTokenScript() {
    return script("issue-action-token.lua");
  }

  @Bean
  public RedisScript<String> consumeActionTokenScript() {
    return script("consume-action-token.lua");
  }

  @Bean
  public ActionTokenStore actionTokenStore(
      StringRedisTemplate stringRedisTemplate,
      AuthRedisKeys authRedisKeys,
      RedisScript<String> issueActionTokenScript,
      RedisScript<String> consumeActionTokenScript) {
    return new LuaActionTokenStore(
        stringRedisTemplate,
        authRedisKeys,
        issueActionTokenScript,
        consumeActionTokenScript,
        Clock.systemUTC());
  }

  private static RedisScript<String> script(String fileName) {
    return RedisScript.of(new ClassPathResource("redis/" + fileName), String.class);
  }
}
