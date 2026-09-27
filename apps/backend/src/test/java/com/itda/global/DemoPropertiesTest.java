package com.itda.global;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class DemoPropertiesTest {

  @Test
  void 아이디와_단어가_모두_있어야_켜진다() {
    assertThat(new DemoProperties("ssafy", List.of("자전거")).enabled()).isTrue();
    assertThat(new DemoProperties("", List.of("자전거")).enabled()).isFalse();
    assertThat(new DemoProperties("ssafy", List.of()).enabled()).isFalse();
    assertThat(new DemoProperties(null, null).enabled()).isFalse();
  }

  @Test
  void 시연_계정만_해당하고_공백은_정리한다() {
    DemoProperties demo = new DemoProperties(" ssafy ", List.of(" 자전거", "", "드라마 "));
    assertThat(demo.isDemoUsername("ssafy")).isTrue();
    assertThat(demo.isDemoUsername("learner01")).isFalse();
    assertThat(demo.learningWords()).containsExactly("자전거", "드라마");
  }
}
