package com.itda.global.config;

import java.net.http.HttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

  @Bean
  public RestClient restClient(@Value("${itda.ai.base-url}") String baseUrl) {
    HttpClient httpClient =
        HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);

    return RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
  }
}
