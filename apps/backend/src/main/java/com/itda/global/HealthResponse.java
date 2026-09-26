package com.itda.global;

import java.time.Instant;

/** health check 응답. time은 ISO 8601 UTC로 나간다 (docs/conventions/code/api.md "날짜·시간"). */
public record HealthResponse(String status, String service, Instant time) {}
