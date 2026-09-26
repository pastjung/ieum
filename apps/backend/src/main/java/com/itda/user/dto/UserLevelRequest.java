package com.itda.user.dto;

import com.itda.problem.domain.Level;
import jakarta.validation.constraints.NotNull;

/**
 * 학습 단계 설정 요청.
 *
 * <p>{@code null}을 받지 않는다 — {@code null}은 "아직 고르지 않음"을 뜻하는 초기 상태이고, 설정 화면이 되돌릴 수 있는 값이 아니다. enum에
 * 없는 문자열은 Spring의 메시지 변환 단계에서 걸러진다.
 */
public record UserLevelRequest(@NotNull Level level) {}
