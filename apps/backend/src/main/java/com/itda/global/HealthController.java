package com.itda.global;

import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

  @GetMapping("/health")
  public HealthResponse health() {
    return new HealthResponse("UP", "itda-backend", Instant.now());
  }
}
