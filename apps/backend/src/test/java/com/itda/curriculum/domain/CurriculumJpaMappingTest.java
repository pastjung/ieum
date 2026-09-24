package com.itda.curriculum.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.itda.problem.domain.Level;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.util.ReflectionTestUtils;

@DataJpaTest
class CurriculumJpaMappingTest {

  @Autowired private TestEntityManager entityManager;

  @Test
  void curriculum_stages_행을_저장하고_다시_읽는다() {
    CurriculumStage saved =
        entityManager.persistFlushFind(stage("한글 자모", Level.CONSONANT, 1, "자모 익히기"));
    entityManager.clear();

    CurriculumStage found = entityManager.find(CurriculumStage.class, saved.getId());

    assertThat(found.getName()).isEqualTo("한글 자모");
    assertThat(found.getLevel()).isEqualTo(Level.CONSONANT);
    assertThat(found.getOrderNo()).isEqualTo(1);
    assertThat(found.getDescription()).isEqualTo("자모 익히기");
  }

  @Test
  void 단계의_level은_enum_이름_문자열로_저장한다() {
    CurriculumStage saved = entityManager.persistFlushFind(stage("단어", Level.WORD, 2, null));
    entityManager.clear();

    Object stored =
        entityManager
            .getEntityManager()
            .createNativeQuery("select level from curriculum_stages where id = :id")
            .setParameter("id", saved.getId())
            .getSingleResult();

    assertThat(stored).isEqualTo("WORD");
  }

  @Test
  void 단계_순서는_전체에서_고유하다() {
    entityManager.persistAndFlush(stage("자음", Level.CONSONANT, 9, null));

    assertThatThrownBy(() -> entityManager.persistAndFlush(stage("모음", Level.VOWEL, 9, null)))
        .isInstanceOf(PersistenceException.class);
  }

  @Test
  void 설명은_비워둘_수_있다() {
    CurriculumStage saved = entityManager.persistFlushFind(stage("문장", Level.SENTENCE, 3, null));
    entityManager.clear();

    assertThat(entityManager.find(CurriculumStage.class, saved.getId()).getDescription()).isNull();
  }

  /**
   * CurriculumStage는 팩토리·공개 생성자가 없고 setter도 없다. 매핑만 검증하면 되므로 필드를 직접 채운다 — 도메인 규칙을 검증하는 것이 아니라
   * 컬럼·제약이 의도대로 걸렸는지만 확인한다.
   */
  private static CurriculumStage stage(String name, Level level, int orderNo, String description) {
    CurriculumStage stage = new CurriculumStage();
    ReflectionTestUtils.setField(stage, "name", name);
    ReflectionTestUtils.setField(stage, "level", level);
    ReflectionTestUtils.setField(stage, "orderNo", orderNo);
    ReflectionTestUtils.setField(stage, "description", description);
    return stage;
  }
}
