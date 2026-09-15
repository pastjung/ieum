package com.itda.global.config;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsProcessor;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.handler.AbstractHandlerMapping;

// 조사 필요 : SPring Bot 4.3.5인가? 부터 Cors 관련을 Annotation으로 처리 가능합니다.
@Configuration
public class WebConfig implements WebMvcConfigurer {

  /**
   * CORS 거부 응답을 body 없이 끝내는 processor. Security chain과 MVC가 같은 인스턴스를 쓴다.
   *
   * <p>CORS 거부 지점은 두 곳이다. REQUEST dispatch는 Security chain의 {@code CorsFilter}가 먼저 판정하고, 그 filter가
   * 건너뛰는 ERROR dispatch({@code /error})는 MVC handler mapping의 CORS interceptor가 판정한다. 한쪽만 바꾸면 다른
   * 쪽에서 Spring 기본 문구가 그대로 나간다.
   */
  private static final CorsProcessor CORS_PROCESSOR = new EmptyBodyCorsProcessor();

  /** Security chain의 {@code CorsFilter}가 주입받는다({@code SecurityConfig}). */
  @Bean
  public CorsProcessor corsProcessor() {
    return CORS_PROCESSOR;
  }

  /**
   * MVC handler mapping마다 같은 processor를 쓰게 한다. {@link WebMvcConfigurer}에는 CORS processor를 지정하는
   * hook이 없다.
   *
   * <p>BeanPostProcessor는 다른 bean보다 먼저 만들어지므로 이 설정 클래스를 일찍 초기화하지 않도록 static으로 둔다.
   */
  @Bean
  public static BeanPostProcessor handlerMappingCorsProcessorPostProcessor() {
    return new BeanPostProcessor() {
      @Override
      public Object postProcessBeforeInitialization(Object bean, String beanName) {
        if (bean instanceof AbstractHandlerMapping handlerMapping) {
          handlerMapping.setCorsProcessor(CORS_PROCESSOR);
        }
        return bean;
      }
    };
  }

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    registry
        .addMapping("/**")
        .allowedOrigins(
            "http://localhost:5173", // web (vite dev)
            "http://localhost:8081" // mobile (metro)
            )
        .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
        .allowedHeaders("*")
        .allowCredentials(true);
  }
}
