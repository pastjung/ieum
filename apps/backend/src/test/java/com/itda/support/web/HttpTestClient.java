package com.itda.support.web;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** JDK HttpClient 래퍼. 임의 method(TRACE 포함)와 임의 헤더를 그대로 보낸다. */
public final class HttpTestClient {

  private static final HttpClient CLIENT =
      HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
  private static final ObjectMapper JSON = JsonMapper.builder().build();

  private final int port;

  public HttpTestClient(int port) {
    this.port = port;
  }

  public HttpResponse<String> get(String path, String... headerPairs) {
    return send("GET", path, null, headerPairs);
  }

  /**
   * @param path context path를 포함한 경로 (예: /api/v1/health)
   * @param body null이면 body 없이 보낸다
   * @param headerPairs 이름, 값 순서의 헤더 목록
   */
  public HttpResponse<String> send(String method, String path, String body, String... headerPairs) {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    for (int i = 0; i < headerPairs.length; i += 2) {
      builder.header(headerPairs[i], headerPairs[i + 1]);
    }
    builder.method(
        method,
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body));
    try {
      return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  public static Map<String, Object> json(HttpResponse<String> response) {
    return JSON.readValue(response.body(), new TypeReference<>() {});
  }
}
