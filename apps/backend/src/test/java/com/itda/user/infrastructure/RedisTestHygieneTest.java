package com.itda.user.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 테스트가 Redis 전체를 비우지 못하게 막는다.
 *
 * <p>개발자의 로컬 Redis에는 이 프로젝트 말고 다른 데이터도 있고, 테스트가 그것을 지우는 것은 테스트의 권한 밖이다. 한 번 들어오면 다음 사람이 눈치채기 어려운
 * 종류의 코드라 자동으로 막는다. 격리는 실행마다 새 식별자를 만들고 자기가 만든 키만 지우는 방식으로 한다.
 *
 * <p>검사어를 조각으로 이어 붙이는 이유는 이 파일 자신이 검사에 걸리지 않게 하기 위해서다.
 */
class RedisTestHygieneTest {

  /** 명령을 실제로 호출하는 형태만 찾는다. 이유를 설명하는 주석 문장까지 막으면 규칙을 적어 둘 수 없다. */
  private static final List<String> FORBIDDEN =
      List.of(
          "'" + "FLUSH" + "DB'",
          "\"" + "FLUSH" + "DB\"",
          "'" + "FLUSH" + "ALL'",
          "\"" + "FLUSH" + "ALL\"",
          "flush" + "Db(",
          "flush" + "All(");

  private static final List<Path> SCANNED =
      List.of(Path.of("src", "test", "java"), Path.of("src", "main", "resources", "redis"));

  @Test
  void 테스트와_스크립트가_Redis_전체를_비우지_않는다() {
    for (Path root : SCANNED) {
      assertThat(Files.isDirectory(root)).as("검사 대상 경로 %s", root).isTrue();
      for (Path file : sourceFilesIn(root)) {
        String content = read(file);
        for (String forbidden : FORBIDDEN) {
          assertThat(content).as("%s 가 %s 를 사용한다", file, forbidden).doesNotContain(forbidden);
        }
      }
    }
  }

  private static List<Path> sourceFilesIn(Path root) {
    try (Stream<Path> paths = Files.walk(root)) {
      return paths.filter(Files::isRegularFile).toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String read(Path file) {
    try {
      return Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
