package com.itda.support.security;

import com.itda.user.domain.Email;
import com.itda.user.domain.User;
import com.itda.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * 인증 상태 조회만 흉내 내는 테스트 대역.
 *
 * <p>저장소 장애를 주입할 수 있어야 한다 — "DB를 읽지 못한 경우"와 "읽었는데 조건이 맞지 않는 경우"의 분기는 실제 예외를 던져 봐야 검증된다.
 */
public class StubUserRepository implements UserRepository {

  private final Map<Long, User> usersById = new HashMap<>();
  private RuntimeException failure;

  /**
   * HTTP 처리 스레드가 증가시키고 테스트 스레드가 읽으므로 원자적 타입을 쓴다. 평범한 int면 두 스레드 사이에 happens-before가 없어 오래된 값이 보일 수
   * 있고, 그 결과 통합 테스트가 부하에 따라 흔들린다.
   */
  private final AtomicInteger findByIdCallCount = new AtomicInteger();

  /**
   * 사용자를 등록한다. 엔티티의 id도 함께 채운다.
   *
   * <p>id는 DB가 발급하므로 {@code User.create}만으로는 비어 있다. key와 엔티티의 id가 어긋나면 id를 읽는 호출자가 이 stub에서만
   * NullPointerException을 만난다.
   */
  public void register(long id, User user) {
    assignId(user, id);
    this.usersById.put(id, user);
  }

  private static void assignId(User user, long id) {
    try {
      java.lang.reflect.Field field = User.class.getDeclaredField("id");
      field.setAccessible(true);
      field.set(user, id);
    } catch (ReflectiveOperationException e) {
      // 엔티티에 setter를 만들지 않는다는 규칙을 테스트 편의로 깨지 않으려고 리플렉션을 쓴다.
      throw new IllegalStateException("테스트 fixture에 id를 넣지 못했습니다.", e);
    }
  }

  /** 트랜잭션을 시작하지 못하는 장애. DataAccessException이 아니라 TransactionException 계열이다. */
  public void willFail() {
    this.failure = new CannotCreateTransactionException("테스트용 트랜잭션 시작 실패");
  }

  /** 트랜잭션은 열렸지만 명령이 실패하는 장애. */
  public void willFailWithDataAccessException() {
    this.failure = new QueryTimeoutException("테스트용 저장소 장애");
  }

  /** 저장소 장애가 아닌 프로그래밍 오류. 인증 계층이 이것까지 503으로 숨기면 안 된다. */
  public void willFailWithProgrammingError() {
    this.failure = new IllegalStateException("테스트용 프로그래밍 오류");
  }

  public int findByIdCallCount() {
    return this.findByIdCallCount.get();
  }

  public void resetFindByIdCallCount() {
    this.findByIdCallCount.set(0);
  }

  @Override
  public Optional<User> findById(Long id) {
    this.findByIdCallCount.incrementAndGet();
    if (this.failure != null) {
      throw this.failure;
    }
    return Optional.ofNullable(this.usersById.get(id));
  }

  @Override
  public User save(User user) {
    throw new UnsupportedOperationException("인증 경로에서는 쓰지 않는다.");
  }

  @Override
  public Optional<User> findByUsername(String username) {
    throw new UnsupportedOperationException("인증 경로에서는 쓰지 않는다.");
  }

  @Override
  public Optional<User> findByEmail(Email email) {
    throw new UnsupportedOperationException("인증 경로에서는 쓰지 않는다.");
  }

  @Override
  public boolean existsByUsername(String username) {
    throw new UnsupportedOperationException("인증 경로에서는 쓰지 않는다.");
  }

  @Override
  public boolean existsByEmail(Email email) {
    throw new UnsupportedOperationException("인증 경로에서는 쓰지 않는다.");
  }

  @Override
  public int markEmailVerified(long userId, long expectedRowVersion, LocalDateTime now) {
    throw new UnsupportedOperationException("인증 경로에서는 쓰지 않는다.");
  }

  @Override
  public int recordLoginFailure(
      long userId, int maxAttempts, LocalDateTime lockedUntil, LocalDateTime now) {
    throw new UnsupportedOperationException("인증 경로에서는 쓰지 않는다.");
  }

  @Override
  public int recordLoginSuccess(
      long userId, long expectedRowVersion, long expectedAuthVersion, LocalDateTime now) {
    throw new UnsupportedOperationException("인증 경로에서는 쓰지 않는다.");
  }

  @Override
  public int resetPassword(
      long userId,
      long expectedRowVersion,
      String expectedPasswordHash,
      long expectedAuthVersion,
      String newPasswordHash,
      LocalDateTime now) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int withdraw(
      long userId,
      long expectedRowVersion,
      String expectedPasswordHash,
      long expectedAuthVersion,
      String pseudonymUsername,
      Email pseudonymEmail,
      String discardedPasswordHash,
      LocalDateTime now) {
    throw new UnsupportedOperationException();
  }
}
