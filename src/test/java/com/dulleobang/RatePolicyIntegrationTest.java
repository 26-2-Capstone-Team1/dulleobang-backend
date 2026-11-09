package com.dulleobang;

import static org.assertj.core.api.Assertions.*;

import com.dulleobang.common.crypto.Hashes;
import com.dulleobang.common.json.StrictJson;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "dulleobang.rate.enabled=true")
class RatePolicyIntegrationTest extends IntegrationTestSupport {
  void fill(String bucket, int count) {
    jdbc.update(
        "INSERT INTO rate_limit_buckets VALUES(?,?,?)",
        bucket,
        Instant.now().getEpochSecond() / 60,
        count);
  }

  @Test
  void checksUserQuota() throws Exception {
    String token = activeToken();
    fill("user:" + owner, 120);
    var response = request("GET", "/api/v1/users/me", token);
    assertThat(response.statusCode()).as(response.body()).isEqualTo(429);
    assertThat(StrictJson.parse(response.body()).path("code").asText())
        .isEqualTo("RATE_LIMIT_EXCEEDED");
    assertThat(Integer.parseInt(response.headers().firstValue("Retry-After").orElseThrow()))
        .isBetween(1, 60);
    assertThat(request("GET", "/api/users/me/furniture", token).statusCode()).isEqualTo(429);
    assertThat(
            jdbc.queryForObject(
                "SELECT MAX(request_count) FROM rate_limit_buckets WHERE bucket=?",
                Integer.class,
                "user:" + owner))
        .isEqualTo(122);
  }

  @Test
  void checksAuthQuota() throws Exception {
    String email = "case@example.test";
    fill(
        "auth-account:" + Hashes.sha256(email.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        5);
    var response =
        json(
            "POST",
            "/api/auth/login",
            Map.of("email", "  CASE@example.test ", "password", "wrong-password"),
            null);
    assertThat(response.statusCode()).as(response.body()).isEqualTo(429);
    jdbc.execute("DELETE FROM rate_limit_buckets");
    fill("auth-ip:127.0.0.1", 10);
    assertThat(json("POST", "/api/v1/auth/login", null, null).statusCode()).isEqualTo(429);
  }

  @Test
  void checksActionQuotas() throws Exception {
    String token = activeToken();
    fill("urls:" + owner, 20);
    assertThat(
            request("GET", "/api/v1/assets/" + UUID.randomUUID() + "/download", token).statusCode())
        .isEqualTo(429);
    fill("ai:" + owner, 10);
    assertThat(
            json(
                    "POST",
                    "/api/v1/ai-jobs/" + UUID.randomUUID() + "/retries",
                    null,
                    token,
                    "Idempotency-Key",
                    UUID.randomUUID().toString())
                .statusCode())
        .isEqualTo(429);
    UUID job = UUID.randomUUID();
    fill("poll:" + owner + ":" + job, 20);
    assertThat(request("GET", "/api/ai-jobs/" + job, token).statusCode()).isEqualTo(429);
    String worker = token(owner, UUID.randomUUID(), true);
    fill("worker:worker", 599);
    assertThat(
            json(
                    "POST",
                    "/api/v1/internal/ai-jobs/" + job + "/claim",
                    Map.of("workerId", "worker"),
                    worker)
                .statusCode())
        .isEqualTo(404);
    assertThat(
            json(
                    "POST",
                    "/api/v1/internal/ai-jobs/" + job + "/heartbeat",
                    Map.of("progressPercent", 1, "stage", "RECONSTRUCTING"),
                    worker)
                .statusCode())
        .isEqualTo(429);
  }
}
