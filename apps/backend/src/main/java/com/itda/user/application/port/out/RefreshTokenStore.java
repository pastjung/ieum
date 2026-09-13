package com.itda.user.application.port.out;

/**
 * Refresh Token의 Redis 상태 outbound port (API 명세 3.6.1절, ERD 3.1.1절).
 *
 * <p>네 연산을 한 interface에 두는 이유는 같은 저장소의 같은 키 집합을 다루기 때문이다. 호출자는 Redis 키 문자열·자료구조·Lua 반환 문자열을 전혀 보지
 * 않고 닫힌 결과만 받는다.
 *
 * <p>어떤 메서드도 토큰 원문을 받지 않는다. Redis에 남는 것은 {@code tokenHash}뿐이다.
 */
public interface RefreshTokenStore {

  /**
   * 회전 전 자원 보호용 사전 조회. <b>정확성의 최종 근거가 아니며 어떤 상태도 바꾸지 않는다.</b>
   *
   * <p>검증된 JWT가 말하는 값을 그대로 {@link RefreshTokenRecord}에 담아 넘긴다. {@code userId}·{@code
   * familyId}·{@code jti}·{@code expiresAtEpochSecond}가 저장된 상태와 하나라도 어긋나면 {@code MISMATCHED}이고,
   * {@code tokenHash}만 다르면 {@code HASH_MISMATCH}다.
   *
   * <p><b>호출 계약.</b> {@code MISMATCHED}에는 토큰이 {@code ACTIVE}지만 계열의 {@code currentJti}와 일치하지 않는 상태도
   * 포함된다. 같은 상태를 {@link #rotate}는 재사용으로 판정하고 같은 원자 연산에서 계열을 폐기하므로, 호출자는 {@code MISMATCHED}만으로 "재사용이
   * 아니다"라고 확정해서는 안 된다. 재사용 탐지와 계열 폐기가 필요한 갱신 흐름의 최종 판정은 {@link #rotate}의 원자적 결과를 따른다.
   *
   * @param submitted 제출된 토큰의 검증된 metadata와 해시
   */
  RefreshTokenLookupResult lookup(RefreshTokenRecord submitted);

  /**
   * 새 계열과 첫 토큰을 등록한다. 이미 있는 식별자를 덮어쓰지 않는다.
   *
   * @param firstToken 만료 시각이 현재보다 뒤여야 한다
   * @throws IllegalArgumentException 만료 시각이 이미 지났을 때. 과거 시각으로 저장하면 Redis가 키를 즉시 지워, 아무 상태도 남기지 못한
   *     처리가 성공으로 보고된다
   */
  RefreshFamilyIssueResult issueFamily(RefreshTokenRecord firstToken);

  /**
   * 기존 토큰을 회전한다. 전제 조건 검사와 상태 전이가 하나의 원자 연산 안에서 일어난다.
   *
   * @param current 제출된 토큰. {@code tokenHash}로 소지 여부를, {@code expiresAtEpochSecond}로 저장된 상태와의 일치를
   *     확인한다
   * @param next 새로 발급한 토큰. {@code familyId}는 {@code current}와 같아야 하고 만료는 {@code current}보다 앞설 수 없다
   *     — 계열이 아직 유효한 토큰보다 먼저 만료되면 재사용을 판별할 수 없다. 만료 시각은 현재보다 뒤여야 한다
   * @throws IllegalArgumentException 두 토큰의 사용자나 계열이 다를 때, {@code next}의 만료가 {@code current}보다 앞설 때,
   *     또는 {@code next}의 만료가 이미 지났을 때. {@code current}의 만료는 여기서 보지 않는다 — 검증과 저장소 만료 사이에서 자연스럽게 지날 수
   *     있어 최종 판정은 원자 연산이 한다
   */
  RefreshRotationResult rotate(RefreshTokenRecord current, RefreshTokenRecord next);

  /**
   * 계열을 폐기한다. 계열 hash의 상태만 바꾸고 토큰 상태는 바꾸지 않는다. 같은 계열에 여러 번 호출해도 결과가 같다.
   *
   * @throws IllegalArgumentException {@code userId}가 양수가 아니거나 {@code familyId}가 canonical UUID v4가
   *     아닐 때. 확인하지 않으면 잘못된 값이 그대로 저장소 키가 된다
   */
  RefreshFamilyRevocationResult revokeFamily(long userId, String familyId);
}
