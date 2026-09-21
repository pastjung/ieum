package com.itda.support.redis;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * 실제 Redis 서버가 있어야 도는 테스트임을 표시한다.
 *
 * <p>인증 상태 저장소에는 인메모리 대체재가 없다. Lua 스크립트의 원자성, {@code EVALSHA} 실패 후 복구, TTL·ZSET score 갱신은 가짜 구현으로는
 * 증명되지 않는다.
 *
 * <p>로컬에 Redis가 없으면 건너뛴다. 다만 {@code ITDA_REQUIRE_REAL_REDIS=true}인 환경(Dockerfile test 스테이지)에서는 건너뛰지
 * 않고 실패한다 — 검증 파이프라인이 조용히 지나가면 "테스트가 통과했다"가 아무것도 보장하지 않는다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ExtendWith(RealRedisCondition.class)
public @interface RequiresRealRedis {}
