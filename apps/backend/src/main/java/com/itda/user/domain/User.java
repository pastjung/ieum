package com.itda.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.text.Normalizer;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * users — 사용자 계정 애그리거트 루트 (ERD 3.1절).
 *
 * <p>한 계정은 LEARNER·INSTRUCTOR·ADMIN 중 하나의 역할만 가진다. 상태 변경은 반드시 도메인 메서드를 통해서만 일어난다 (setter 금지).
 *
 * <p>시각 인자를 밖에서 받는 이유: ERD 7절이 서버 영속 시각을 UTC DB 시각으로 못박고 자동 ON UPDATE를 금지한다. 엔티티가 스스로 시계를 읽으면 한
 * 트랜잭션 안에서 시각이 어긋나고 테스트도 비결정적이 된다.
 *
 * <p>DB 제약 중 ERD가 요구하는 CHECK(failed_login_count >= 0)와 DEFAULT는 스키마 원본을 엔티티로 정한 결과 DB에 반영되지 않는다. 아래
 * 불변식이 그 역할을 대신하며, Flyway 도입 시 DB 제약으로 옮긴다.
 */
@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

  /** ERD 3.1절: 5회 연속 실패 시 제한한다. */
  public static final int MAX_FAILED_LOGIN_ATTEMPTS = 5;

  /** ERD 3.1절: 제한 시간은 10분이다. */
  public static final Duration LOGIN_LOCK_DURATION = Duration.ofMinutes(10);

  private static final int USERNAME_MAX_LENGTH = 50;

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /**
   * 로그인 식별자. 앞뒤 공백 제거·NFC 정규화한 값을 저장한다.
   *
   * <p>대소문자 비구분 중복 검사는 MariaDB의 기본 대소문자 무시 collation에 UNIQUE를 걸어 달성한다. H2(MySQL 모드)는 기본이 대소문자 구분이므로
   * 이 성질은 실제 MariaDB에서만 성립한다 — collation 고정은 Flyway 이슈로 넘긴다.
   */
  @Column(nullable = false, unique = true, length = USERNAME_MAX_LENGTH)
  private String username;

  /** Argon2id 인코딩 결과. 비밀번호 원문은 저장하지 않는다. */
  @Column(name = "password_hash", nullable = false, length = 255)
  private String passwordHash;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private UserRole role;

  /**
   * 현재까지 열린 최고 단계 코드 (curriculum_stages.level 값). ERD에 물리 FK가 없어 String으로 둔다.
   *
   * <p>자가 진단 전 학습자와 INSTRUCTOR·ADMIN은 NULL이다.
   */
  @Column(length = 30)
  private String level;

  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  /** ERD 3.1절: 자동 ON UPDATE 없이 백엔드가 명시 갱신한다. 그래서 @UpdateTimestamp를 쓰지 않는다. */
  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;

  @Embedded private Email email;

  @Column(name = "email_verified_at")
  private LocalDateTime emailVerifiedAt;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private UserStatus status;

  @Column(name = "withdrawn_at")
  private LocalDateTime withdrawnAt;

  @Column(name = "failed_login_count", nullable = false)
  private int failedLoginCount;

  @Column(name = "locked_until")
  private LocalDateTime lockedUntil;

  /**
   * 기존 Access·Refresh 인증 세션 무효화 세대 (ERD 3.1절). 보호 API는 Access Token의 authVersion claim을 이 값과 비교한다.
   *
   * <p>증가는 비밀번호 재설정·탈퇴가 각자의 트랜잭션에서 수행한다. 이번 이슈는 비교에 필요한 매핑만 두고 전이 메서드를 만들지 않는다.
   *
   * <p>ERD의 CHECK(auth_version >= 0)와 DEFAULT 0은 스키마 원본을 엔티티로 정한 결과 DB에 반영되지 않는다. 생성 시 0으로 두는 아래
   * 불변식이 그 역할을 대신한다.
   */
  @Column(name = "auth_version", nullable = false)
  private long authVersion;

  /**
   * 낙관적 잠금 값 (ERD 3.1절 {@code row_version}).
   *
   * <p>{@code auth_version}과 역할이 다르다. {@code auth_version}은 의도적으로 올려 기존 세션을 끊고 JWT claim으로 실리지만, 이
   * 값은 외부에 노출되지 않고 행이 바뀔 때마다 증가해 stale write만 막는다. 인증 판정에 쓰지 않는다.
   *
   * <p>JPA가 관리하므로 도메인 메서드로 바꾸지 않는다. 생성 시각의 최초 값도 Hibernate가 넣는다.
   */
  @Version
  @Column(name = "row_version", nullable = false)
  private long rowVersion;

  private User(
      String username, String passwordHash, UserRole role, Email email, LocalDateTime now) {
    this.username = normalizeUsername(username);
    this.passwordHash = requireText(passwordHash, "비밀번호 해시");
    this.role = requireNonNull(role, "역할");
    this.email = requireNonNull(email, "이메일");
    this.status = UserStatus.ACTIVE;
    this.failedLoginCount = 0;
    this.authVersion = 0;
    this.createdAt = requireNonNull(now, "생성 시각");
    this.updatedAt = now;
  }

  /**
   * 계정을 만든다. 이메일 인증 전 상태이므로 로그인·학습은 아직 허용되지 않는다.
   *
   * @param now UTC 기준 현재 시각
   */
  public static User create(
      String username, String passwordHash, UserRole role, Email email, LocalDateTime now) {
    return new User(username, passwordHash, role, email, now);
  }

  /** 이메일 인증 완료. 이미 인증된 계정의 인증 시각은 덮어쓰지 않는다. */
  public void verifyEmail(LocalDateTime now) {
    requireActive();
    if (emailVerifiedAt != null) {
      return;
    }
    this.emailVerifiedAt = now;
    this.updatedAt = now;
  }

  public boolean isEmailVerified() {
    return emailVerifiedAt != null;
  }

  /** ERD 3.1절: 이메일 인증 전에는 status가 ACTIVE여도 로그인·학습을 허용하지 않는다. */
  public boolean isLoginAllowed(LocalDateTime now) {
    return status == UserStatus.ACTIVE && isEmailVerified() && !isLocked(now);
  }

  public boolean isLocked(LocalDateTime now) {
    return lockedUntil != null && now.isBefore(lockedUntil);
  }

  /** 로그인 실패 1회 기록. 연속 실패가 한계에 닿으면 제한 종료 시각을 설정한다. */
  public void recordLoginFailure(LocalDateTime now) {
    this.failedLoginCount++;
    if (failedLoginCount >= MAX_FAILED_LOGIN_ATTEMPTS) {
      this.lockedUntil = now.plus(LOGIN_LOCK_DURATION);
    }
    this.updatedAt = now;
  }

  /** 로그인 성공 시 실패 상태를 초기화한다 (ERD 3.1절). */
  public void resetLoginFailures(LocalDateTime now) {
    this.failedLoginCount = 0;
    this.lockedUntil = null;
    this.updatedAt = now;
  }

  public void changePasswordHash(String newPasswordHash, LocalDateTime now) {
    requireActive();
    this.passwordHash = requireText(newPasswordHash, "비밀번호 해시");
    this.updatedAt = now;
  }

  /**
   * 열린 최고 단계를 설정한다.
   *
   * <p>"과거 단계 복습은 level을 낮추지 않는다"(ERD 4.7절)는 단계 순서를 알아야 판정할 수 있고 그 순서는 curriculum_stages.order_no에
   * 있다. 다른 컨텍스트의 데이터이므로 단조성 검증은 애플리케이션 서비스가 담당한다.
   */
  public void changeLevel(String newLevel, LocalDateTime now) {
    requireActive();
    this.level = newLevel;
    this.updatedAt = now;
  }

  /**
   * 탈퇴. 행을 삭제하지 않고 상태 전이·가명화를 한 번에 적용한다 (ERD 3.1절).
   *
   * <p>가명 식별자와 폐기된 비밀번호 해시는 무작위 값 생성이 필요해 서비스가 만들어 넘긴다. 엔티티는 "status·withdrawn_at·인증 초기화·가명화가 함께
   * 일어난다"는 불변식만 강제한다. Refresh Token 폐기는 Redis 작업이며 이 트랜잭션 밖이다.
   */
  public void withdraw(
      String pseudonymizedUsername,
      Email pseudonymizedEmail,
      String discardedPasswordHash,
      LocalDateTime now) {
    requireActive();
    this.status = UserStatus.WITHDRAWN;
    this.withdrawnAt = requireNonNull(now, "탈퇴 시각");
    this.emailVerifiedAt = null;
    this.username = normalizeUsername(pseudonymizedUsername);
    this.email = requireNonNull(pseudonymizedEmail, "가명 이메일");
    this.passwordHash = requireText(discardedPasswordHash, "폐기 비밀번호 해시");
    this.updatedAt = now;
  }

  public boolean isWithdrawn() {
    return status == UserStatus.WITHDRAWN;
  }

  private void requireActive() {
    if (status != UserStatus.ACTIVE) {
      throw new IllegalStateException("탈퇴한 계정은 변경할 수 없습니다.");
    }
  }

  private static String normalizeUsername(String rawValue) {
    String normalized =
        Normalizer.normalize(requireText(rawValue, "아이디").trim(), Normalizer.Form.NFC);
    if (normalized.isEmpty() || normalized.length() > USERNAME_MAX_LENGTH) {
      throw new IllegalArgumentException("아이디는 1~" + USERNAME_MAX_LENGTH + "자여야 합니다.");
    }
    return normalized;
  }

  private static String requireText(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + "은(는) 필수입니다.");
    }
    return value;
  }

  private static <T> T requireNonNull(T value, String fieldName) {
    if (value == null) {
      throw new IllegalArgumentException(fieldName + "은(는) 필수입니다.");
    }
    return value;
  }
}
