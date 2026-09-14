package com.itda.user.controller;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 플랫폼 판정 filter의 등록 구성. */
@Configuration(proxyBeanMethods = false)
public class ClientPlatformFilterConfiguration {

  /**
   * 플랫폼 판정 filter를 플랫폼 헤더가 필수인 세 경로에만, Security chain보다 앞 순서로 등록한다.
   *
   * <p>url pattern으로 경로를 좁히므로 filter 안에서 경로를 다시 비교하지 않는다. dispatcher type 기본값(REQUEST)이라 이 filter가
   * 만든 ERROR dispatch에 다시 들어가지도 않는다.
   */
  @Bean
  public FilterRegistrationBean<ClientPlatformFilter> clientPlatformFilterRegistration() {
    FilterRegistrationBean<ClientPlatformFilter> registration =
        new FilterRegistrationBean<>(new ClientPlatformFilter());
    registration.setUrlPatterns(ClientPlatformFilter.PATHS);
    registration.setOrder(ClientPlatformFilter.ORDER);
    return registration;
  }
}
