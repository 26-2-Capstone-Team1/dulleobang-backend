package com.dulleobang.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("dulleobang")
public record BackendProperties(
    String jwtSecret, String workerSecret, String storageSecret, String corsOrigins) {
  public BackendProperties {
    if (jwtSecret == null
        || workerSecret == null
        || storageSecret == null
        || jwtSecret.length() < 32
        || workerSecret.length() < 32
        || storageSecret.length() < 32
        || jwtSecret.equals(workerSecret)
        || jwtSecret.equals(storageSecret)
        || workerSecret.equals(storageSecret)) {
      throw new IllegalArgumentException("Set three distinct secrets of at least 32 characters.");
    }
  }
}
