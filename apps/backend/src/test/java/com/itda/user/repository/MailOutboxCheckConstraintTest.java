package com.itda.user.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import jakarta.persistence.EntityManager;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

/**
 * ERD 3.5절이 요구하는 {@code mail_outbox} CHECK 네 개가 <b>DB에</b> 있는지 확인한다.
 *
 * <p>{@link com.itda.user.domain.MailOutboxEntry}의 Java 불변식 테스트와 의도적으로 분리했다. 상태 전이가 엔티티를 거치지 않는 조건부
 * UPDATE로 일어나므로, 생성자 검증이 통과한다는 사실은 DB가 잘못된 행을 거부한다는 증거가 아니다. 여기서는 엔티티를 아예 쓰지 않고 native SQL로 직접 넣어
 * 본다.
 *
 * <p><b>이 테스트는 H2에서 돈다.</b> CHECK 식에 H2 전용 문법을 쓰지 않았지만, 실제 MariaDB가 같은 제약을 만들고 같은 행을 거부하는지는 MariaDB
 * 테스트 harness가 생긴 뒤 별도로 확인한다. {@code ddl-auto: validate}는 테이블·컬럼·타입까지만 보므로 운영 스키마에 이 제약이 있다는 근거가 되지
 * 않는다.
 */
@DataJpaTest
class MailOutboxCheckConstraintTest {

  private static final List<String> EXPECTED_CONSTRAINTS =
      List.of(
          "ck_mail_outbox_attempt_count",
          "ck_mail_outbox_delivery_deadline",
          "ck_mail_outbox_pending_state",
          "ck_mail_outbox_terminal_state");

  private static final String PENDING_COLUMNS =
      "purpose, recipient_email, status, attempt_count,"
          + " next_attempt_at, delivery_deadline_at, created_at, updated_at";

  private static final String TERMINAL_COLUMNS =
      "purpose, recipient_email, status, attempt_count,"
          + " next_attempt_at, delivery_deadline_at, completed_at, created_at, updated_at";

  @Autowired private EntityManager entityManager;

  private void insert(String columns, String values) {
    this.entityManager
        .createNativeQuery("insert into mail_outbox (" + columns + ") values (" + values + ")")
        .executeUpdate();
    this.entityManager.flush();
  }

  /**
   * 지정한 CHECK가 실제로 걸려서 거부됐는지 확인한다.
   *
   * <p>"예외가 났다"만 보면 SQL 오타로도 통과한다. 어느 제약이 막았는지까지 확인해야 이 테스트가 계약을 지킨다. H2는 제약 이름을 대문자로, MariaDB는
   * 소문자로 보고하므로 소문자로 맞춰 비교한다.
   */
  private void assertRejectedBy(String constraintName, Runnable statement) {
    Throwable thrown = catchThrowable(statement::run);

    assertThat(thrown).as("%s가 막지 않았습니다.", constraintName).isNotNull();
    StringWriter trace = new StringWriter();
    thrown.printStackTrace(new PrintWriter(trace));
    assertThat(trace.toString().toLowerCase(Locale.ROOT)).contains(constraintName);
  }

  /** 계약을 만족하는 PENDING 행. 각 테스트는 여기서 한 가지만 어긋나게 바꾼다. */
  private void insertValidPending() {
    insert(
        PENDING_COLUMNS,
        "'EMAIL_VERIFICATION', 'learner@example.com', 'PENDING', 0,"
            + " timestamp '2026-09-13 09:00:00', timestamp '2026-09-14 09:00:00',"
            + " timestamp '2026-09-13 09:00:00', timestamp '2026-09-13 09:00:00'");
  }

  @Test
  void 네_CHECK가_이름대로_스키마에_있다() {
    List<?> names =
        this.entityManager
            .createNativeQuery(
                """
                select lower(constraint_name) from information_schema.table_constraints
                 where lower(table_name) = 'mail_outbox' and constraint_type = 'CHECK'
                """)
            .getResultList();

    assertThat(names.stream().map(String::valueOf).toList()).containsAll(EXPECTED_CONSTRAINTS);
  }

  @Test
  void 계약을_만족하는_PENDING_행은_들어간다() {
    insertValidPending();

    Number count =
        (Number)
            this.entityManager
                .createNativeQuery("select count(*) from mail_outbox")
                .getSingleResult();
    assertThat(count.longValue()).isEqualTo(1);
  }

  @Test
  void 음수_시도_횟수를_거부한다() {
    assertRejectedBy(
        "ck_mail_outbox_attempt_count",
        () ->
            insert(
                PENDING_COLUMNS,
                "'EMAIL_VERIFICATION', 'learner@example.com', 'PENDING', -1,"
                    + " timestamp '2026-09-13 09:00:00', timestamp '2026-09-14 09:00:00',"
                    + " timestamp '2026-09-13 09:00:00', timestamp '2026-09-13 09:00:00'"));
  }

  @Test
  void 접수_시각보다_이르거나_같은_기한을_거부한다() {
    assertRejectedBy(
        "ck_mail_outbox_delivery_deadline",
        () ->
            insert(
                PENDING_COLUMNS,
                "'PASSWORD_RESET', 'learner@example.com', 'PENDING', 0,"
                    + " timestamp '2026-09-13 09:00:00', timestamp '2026-09-13 09:00:00',"
                    + " timestamp '2026-09-13 09:00:00', timestamp '2026-09-13 09:00:00'"));
  }

  @Test
  void 수신_주소가_없는_PENDING_행을_거부한다() {
    assertRejectedBy(
        "ck_mail_outbox_pending_state",
        () ->
            insert(
                PENDING_COLUMNS,
                "'EMAIL_VERIFICATION', null, 'PENDING', 0,"
                    + " timestamp '2026-09-13 09:00:00', timestamp '2026-09-14 09:00:00',"
                    + " timestamp '2026-09-13 09:00:00', timestamp '2026-09-13 09:00:00'"));
  }

  @Test
  void 다음_시각이_없는_PENDING_행을_거부한다() {
    assertRejectedBy(
        "ck_mail_outbox_pending_state",
        () ->
            insert(
                PENDING_COLUMNS,
                "'EMAIL_VERIFICATION', 'learner@example.com', 'PENDING', 0,"
                    + " null, timestamp '2026-09-14 09:00:00',"
                    + " timestamp '2026-09-13 09:00:00', timestamp '2026-09-13 09:00:00'"));
  }

  @Test
  void 종결_시각이_있는_PENDING_행을_거부한다() {
    assertRejectedBy(
        "ck_mail_outbox_pending_state",
        () ->
            insert(
                TERMINAL_COLUMNS,
                "'EMAIL_VERIFICATION', 'learner@example.com', 'PENDING', 0,"
                    + " timestamp '2026-09-13 09:00:00', timestamp '2026-09-14 09:00:00',"
                    + " timestamp '2026-09-13 09:00:00',"
                    + " timestamp '2026-09-13 09:00:00', timestamp '2026-09-13 09:00:00'"));
  }

  @Test
  void 수신_주소가_남은_종결_행을_거부한다() {
    assertRejectedBy(
        "ck_mail_outbox_terminal_state",
        () ->
            insert(
                TERMINAL_COLUMNS,
                "'EMAIL_VERIFICATION', 'learner@example.com', 'PROVIDER_ACCEPTED', 1,"
                    + " null, timestamp '2026-09-14 09:00:00', timestamp '2026-09-13 09:10:00',"
                    + " timestamp '2026-09-13 09:00:00', timestamp '2026-09-13 09:10:00'"));
  }

  @Test
  void 종결_시각이_없는_종결_행을_거부한다() {
    assertRejectedBy(
        "ck_mail_outbox_terminal_state",
        () ->
            insert(
                TERMINAL_COLUMNS,
                "'PASSWORD_RESET', null, 'FAILED', 6,"
                    + " null, timestamp '2026-09-14 09:00:00', null,"
                    + " timestamp '2026-09-13 09:00:00', timestamp '2026-09-13 09:10:00'"));
  }

  @Test
  void 종결_상태로_바꾸면서_수신_주소를_남기는_UPDATE를_거부한다() {
    insertValidPending();

    // 엔티티를 거치지 않는 갱신도 DB가 막아야 한다. 조건부 UPDATE가 실제 전이 경로이기 때문이다.
    assertRejectedBy(
        "ck_mail_outbox_terminal_state",
        () -> {
          this.entityManager
              .createNativeQuery(
                  """
                  update mail_outbox
                     set status = 'FAILED', completed_at = timestamp '2026-09-13 09:10:00'
                  """)
              .executeUpdate();
          this.entityManager.flush();
        });
  }
}
