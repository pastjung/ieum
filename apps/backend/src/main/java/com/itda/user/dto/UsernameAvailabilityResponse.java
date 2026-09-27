package com.itda.user.dto;

/**
 * {@code GET /api/v1/username-availability} 응답 body (API 명세 3.2.1절).
 *
 * <p>{@code username}은 요청받은 원문이 아니라 <b>정규화된 값</b>이다. trim·NFC 정규화로 요청 값과 달라질 수 있고, 클라이언트는 무엇에 대한
 * 판정인지 알아야 사용자에게 "이 아이디는 쓸 수 있어요"라고 정확히 안내할 수 있다.
 *
 * <p>{@code available}은 <b>조회 시점의 판정일 뿐이다</b>. 조회와 실제 가입 사이에 다른 요청이 같은 값을 선점할 수 있으므로 {@code true}가
 * 이후 가입의 {@code 201}을 보장하지 않는다. 최종 판정은 {@code POST /api/v1/users}의 {@code 409}다.
 *
 * <p>{@code toString()}을 가리지 않는다. 다른 계정 DTO와 달리 여기에는 비밀번호·토큰·이메일이 없고, 아이디는 가입 화면이 화면에 그대로 보여 주는
 * 값이다. 가릴 것이 없는데 가리면 로그에서 원인을 찾기만 어려워진다.
 */
public record UsernameAvailabilityResponse(String username, boolean available) {}
