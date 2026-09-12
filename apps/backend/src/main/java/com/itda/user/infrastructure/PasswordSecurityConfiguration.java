package com.itda.user.infrastructure;

import com.itda.user.application.NewPasswordService;
import com.itda.user.application.PasswordVerificationService;
import com.itda.user.application.port.out.CompromisedPasswordChecker;
import com.itda.user.application.port.out.PasswordHasher;
import java.net.http.HttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 비밀번호 정책 관련 bean 구성.
 *
 * <p>설정 properties 등록을 여기에 모아 두는 이유는 {@code ItdaApplication}에 스캔 애너테이션을 덧붙이지 않기 위해서다. 이 도메인의 설정이 전역
 * 진입점에 흩어지면 어떤 properties가 살아 있는지 한곳에서 알 수 없다.
 *
 * <p>application 계층 클래스에 Spring 애너테이션을 붙이지 않고 여기서 조립한다 — 유스케이스는 프레임워크 없이도 생성·테스트할 수 있어야 한다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({Argon2idProperties.class, BreachCheckProperties.class})
public class PasswordSecurityConfiguration {

  @Bean
  public PasswordHasher passwordHasher(Argon2idProperties properties) {
    return new Argon2idPasswordHasher(properties);
  }

  /** 연결·읽기 타임아웃을 분리해 설정한다. 둘 다 없으면 제공자 장애가 요청 스레드를 무기한 붙잡는다. */
  @Bean
  public CompromisedPasswordChecker compromisedPasswordChecker(BreachCheckProperties properties) {
    HttpClient httpClient =
        HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(properties.readTimeout());
    RestClient restClient =
        RestClient.builder().baseUrl(properties.baseUrl()).requestFactory(requestFactory).build();
    return new HibpRangePasswordChecker(restClient);
  }

  @Bean
  public NewPasswordService newPasswordService(
      CompromisedPasswordChecker compromisedPasswordChecker, PasswordHasher passwordHasher) {
    return new NewPasswordService(compromisedPasswordChecker, passwordHasher);
  }

  @Bean
  public PasswordVerificationService passwordVerificationService(PasswordHasher passwordHasher) {
    return new PasswordVerificationService(passwordHasher);
  }
}
