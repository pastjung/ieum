package com.itda.voice.cartesia;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Cartesia 게이트웨이 조립.
 *
 * <p>{@link RestClient} 를 bean으로 두지 않는다. FastAPI용 {@code RestClientConfig.restClient} 가 유일한 {@code
 * RestClient} bean이라는 전제로 {@code FastApiClient} 가 타입 주입을 받기 때문이다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CartesiaProperties.class)
class CartesiaConfiguration {

  @Bean
  VoiceSynthesisGateway voiceSynthesisGateway(CartesiaProperties properties) {
    HttpClient httpClient =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(properties.connectTimeout())
            .build();
    return new CartesiaVoiceGateway(
        restClient(httpClient, properties.baseUrl(), properties.cloneTimeout()),
        restClient(httpClient, properties.baseUrl(), properties.speechTimeout()),
        properties.apiKey(),
        properties.apiVersion());
  }

  private static RestClient restClient(
      HttpClient httpClient, String baseUrl, Duration readTimeout) {
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(readTimeout);
    return RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
  }
}
