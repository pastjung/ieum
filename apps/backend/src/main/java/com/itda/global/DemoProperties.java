package com.itda.global;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 발표 시연 계정 설정 ({@code itda.demo}).
 *
 * <p>이 계정이 '단어' 테마로 학습하기를 시작하면 {@code learningWords} 만 적힌 순서대로 나오고, 복습하기는 약한 스킬 순위대로(1순위 스킬의 문제 4개가
 * 먼저) 섞지 않고 나온다. 매번 같은 흐름을 재현하기 위해서다. 다른 계정은 영향을 받지 않는다.
 *
 * <p>{@code username} 을 비우면 아무 계정도 해당하지 않는다.
 */
@ConfigurationProperties("itda.demo")
public record DemoProperties(
    @DefaultValue("") String username, @DefaultValue({}) List<String> learningWords) {

  public DemoProperties {
    username = username == null ? "" : username.strip();
    learningWords =
        learningWords == null
            ? List.of()
            : learningWords.stream().map(String::strip).filter(w -> !w.isEmpty()).toList();
  }

  public boolean enabled() {
    return !username.isEmpty() && !learningWords.isEmpty();
  }

  public boolean isDemoUsername(String candidate) {
    return enabled() && username.equals(candidate);
  }
}
