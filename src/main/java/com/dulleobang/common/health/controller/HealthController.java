package com.dulleobang.common.health.controller;

import com.dulleobang.common.health.service.HealthService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
  private final HealthService service;

  public HealthController(HealthService service) {
    this.service = service;
  }

  @GetMapping({"/", "/health/live"})
  public Map<String, String> live() {
    return Map.of("status", "ok");
  }

  @GetMapping("/health/ready")
  public Map<String, String> ready() {
    service.check();
    return Map.of("status", "ready");
  }
}
