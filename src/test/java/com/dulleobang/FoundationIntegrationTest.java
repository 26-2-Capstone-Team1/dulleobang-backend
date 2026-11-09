package com.dulleobang;

import static org.assertj.core.api.Assertions.*;

import com.dulleobang.common.exception.ApiException;
import com.dulleobang.common.idempotency.*;
import com.dulleobang.common.json.StrictJson;
import com.nimbusds.jose.*;
import com.nimbusds.jwt.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;

class FoundationIntegrationTest extends IntegrationTestSupport {
  @Test
  void handlesHttpNegotiation() throws Exception {
    var response =
        http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health/live"))
                .header("Accept", "text/plain")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).as(response.body()).isEqualTo(406);
    assertThat(StrictJson.parse(response.body()).path("code").asText()).isEqualTo("NOT_ACCEPTABLE");
    var method = request("POST", "/health/live", null);
    assertThat(method.statusCode()).as(method.body()).isEqualTo(405);
    assertThat(method.headers().firstValue("Allow").orElseThrow()).contains("GET");
    assertThat(StrictJson.parse(method.body()).path("code").asText())
        .isEqualTo("METHOD_NOT_ALLOWED");
  }

  @Test
  void guardsHttpMethods() throws Exception {
    assertThat(request("HEAD", "/api/users/me", null).statusCode()).isEqualTo(405);
    assertThat(request("HEAD", "/api/users/me", activeToken()).statusCode()).isEqualTo(405);
    assertThat(request("OPTIONS", "/api/users/me", null).statusCode()).isEqualTo(405);
  }

  @Test
  void realPostgresFlywayAndJpa() {
    assertThat(jdbc.queryForObject("SELECT version()", String.class)).contains("PostgreSQL");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success", Integer.class))
        .isGreaterThanOrEqualTo(2);
    assertThat(users.findById(owner)).isPresent();
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO sessions VALUES(?,?,CURRENT_TIMESTAMP,FALSE)",
                    UUID.randomUUID(),
                    UUID.randomUUID()))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  }

  @Test
  void healthAndCommonProblemOverHttp() throws Exception {
    assertThat(request("GET", "/health/live", null).statusCode()).isEqualTo(200);
    assertThat(request("GET", "/health/ready", null).statusCode()).isEqualTo(200);
    var denied = request("GET", "/api/listings", null);
    assertThat(denied.statusCode()).isEqualTo(401);
    assertThat(denied.headers().firstValue("WWW-Authenticate")).contains("Bearer");
    var error = StrictJson.parse(denied.body());
    assertThat(error.path("code").asText()).isEqualTo("UNAUTHORIZED");
    assertThat(error.path("requestId").asText())
        .isEqualTo(denied.headers().firstValue("X-Request-Id").orElseThrow());
    assertThat(request("GET", "/api/does-not-exist", null).statusCode()).isEqualTo(404);
  }

  @Test
  void independentFoundationHttpScenarios() throws Exception {
    var process =
        new ProcessBuilder(
                System.getenv().getOrDefault("V2_PYTHON", "python"),
                "contract-tests/run.py",
                "--base-url",
                "http://127.0.0.1:" + port,
                "--profile",
                "foundation",
                "--report",
                "verification/foundation-http-contracts.json")
            .inheritIO()
            .start();
    assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
    assertThat(process.exitValue()).isZero();
  }

  @Test
  void checksApiInventory() throws Exception {
    assertThat(inventory.routes().stream().filter(r -> r.path().startsWith("/api/")).count())
        .isEqualTo(97);
    for (var route : inventory.routes()) {
      if (!route.path().startsWith("/api/")) continue;
      String path = route.path().replaceAll("\\{[^}]+\\}", "11111111-1111-4111-8111-111111111111");
      var response = request(route.method(), path, null);
      assertThat(response.statusCode())
          .as(route.method() + " " + path)
          .isEqualTo(
              inventory.isPublic(route)
                  ? (route.operationId().equals("refreshToken") ? 400 : 422)
                  : 401);
    }
  }

  @Test
  void validatesSessionAndWorkerIsolation() throws Exception {
    UUID sid = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO sessions VALUES(?,?,?,FALSE)",
        sid,
        owner,
        java.sql.Timestamp.from(Instant.now().plusSeconds(900)));
    String bearer = token(owner, sid, false);
    assertThat(request("GET", "/api/listings", bearer).statusCode()).isEqualTo(200);
    assertThat(request("GET", "/api/listings", token(owner, sid, true)).statusCode())
        .isEqualTo(401);
    assertThat(
            request("POST", "/api/v1/internal/ai-jobs/" + owner + "/claim", token(owner, sid, true))
                .statusCode())
        .isEqualTo(422);
    jdbc.update("UPDATE sessions SET revoked=TRUE WHERE id=?", sid);
    assertThat(request("GET", "/api/listings", bearer).statusCode()).isEqualTo(401);
  }

  @Test
  void corsIsExplicit() throws Exception {
    var req =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/listings"))
            .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
            .header("Origin", "http://localhost:3000")
            .header("Access-Control-Request-Method", "GET")
            .build();
    var response = http.send(req, HttpResponse.BodyHandlers.ofString());
    assertThat(response.headers().firstValue("Access-Control-Allow-Origin"))
        .contains("http://localhost:3000");
    var bad =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/listings"))
            .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
            .header("Origin", "https://evil.invalid")
            .header("Access-Control-Request-Method", "GET")
            .build();
    assertThat(http.send(bad, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
  }

  @Test
  void commitsOnce() throws Exception {
    var key = UUID.randomUUID().toString();
    var count = new AtomicInteger();
    var gate = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(6);
    try {
      List<Future<StoredResponse>> jobs = new ArrayList<>();
      for (int i = 0; i < 6; i++)
        jobs.add(
            executor.submit(
                () -> {
                  gate.await();
                  return idempotency.execute(
                      owner.toString(),
                      "POST",
                      "/fixture",
                      key,
                      "{\"b\":2,\"a\":1}",
                      () -> {
                        count.incrementAndGet();
                        return new StoredResponse(201, "private-token", Map.of("ETag", "\"1\""));
                      });
                }));
      gate.countDown();
      for (var job : jobs)
        assertThat(job.get(20, TimeUnit.SECONDS).body()).isEqualTo("private-token");
      assertThat(count.get()).isEqualTo(1);
      assertThat(
              idempotency
                  .execute(
                      owner.toString(),
                      "POST",
                      "/fixture",
                      key,
                      "{\"a\":1,\"b\":2}",
                      () -> {
                        throw new AssertionError();
                      })
                  .status())
          .isEqualTo(201);
      assertThatThrownBy(
              () ->
                  idempotency.execute(owner.toString(), "POST", "/fixture", key, "{}", () -> null))
          .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(409));
      byte[] payload = jdbc.queryForObject("SELECT payload FROM idempotency_records", byte[].class);
      assertThat(new String(payload, java.nio.charset.StandardCharsets.ISO_8859_1))
          .doesNotContain("private-token");
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void rollsBackRequest() {
    UUID failed = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                idempotency.execute(
                    owner.toString(),
                    "POST",
                    "/rollback",
                    UUID.randomUUID().toString(),
                    "{}",
                    () -> {
                      jdbc.update(
                          "INSERT INTO users(user_id,display_name) VALUES(?,?)",
                          failed,
                          "rollback");
                      throw new IllegalStateException("fail");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(users.findById(failed)).isEmpty();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_records", Integer.class))
        .isZero();
  }

  @Test
  void checksReplayScopes() {
    String key = UUID.randomUUID().toString();
    var response = new StoredResponse(201, "ok", Map.of());
    idempotency.execute(owner.toString(), "POST", "/a", key, "{}", () -> response);
    idempotency.execute(owner.toString(), "POST", "/b", key, "{}", () -> response);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_records", Integer.class))
        .isEqualTo(2);
    jdbc.update("UPDATE idempotency_records SET expires_at=CURRENT_TIMESTAMP-INTERVAL '1 second'");
    assertThat(
            idempotency.execute(
                owner.toString(), "POST", "/a", key, "{\"new\":true}", () -> response))
        .isEqualTo(response);
  }

  @Test
  void checksResponseCipher() {
    byte[] bytes = cipher.encrypt("private", "scope");
    assertThat(cipher.decrypt(bytes, "scope")).isEqualTo("private");
    assertThatThrownBy(() -> cipher.decrypt(bytes, "another"))
        .isInstanceOf(IllegalStateException.class);
    bytes[bytes.length - 1] ^= 1;
    assertThatThrownBy(() -> cipher.decrypt(bytes, "scope"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void checksCursorBinding() {
    var first = cursors.start(owner, "/api/v1/listings", Map.of("limit", 1), List.of("a", "b"), 1);
    assertThat(first.ids()).containsExactly("a");
    assertThat(first.hasNext()).isTrue();
    var next = cursors.resume(owner, "/api/v1/listings", Map.of("limit", 1), first.nextCursor(), 1);
    assertThat(next.ids()).containsExactly("b");
    assertThat(next.hasNext()).isFalse();
    assertThatThrownBy(
            () ->
                cursors.resume(
                    UUID.randomUUID(),
                    "/api/v1/listings",
                    Map.of("limit", 1),
                    first.nextCursor(),
                    1))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(
            () ->
                cursors.resume(
                    owner, "/api/v1/furniture", Map.of("limit", 1), first.nextCursor(), 1))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(
            () ->
                cursors.resume(
                    owner, "/api/v1/listings", Map.of("limit", 2), first.nextCursor(), 1))
        .isInstanceOf(ApiException.class);
    jdbc.execute("UPDATE cursor_snapshots SET expires_at=CURRENT_TIMESTAMP-INTERVAL '1 second'");
    assertThatThrownBy(
            () ->
                cursors.resume(
                    owner, "/api/v1/listings", Map.of("limit", 1), first.nextCursor(), 1))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void rateBucketIsAtomic() throws Exception {
    var executor = Executors.newFixedThreadPool(8);
    var accepted = new AtomicInteger();
    try {
      List<Future<?>> jobs = new ArrayList<>();
      for (int i = 0; i < 8; i++)
        jobs.add(
            executor.submit(
                () -> {
                  try {
                    rates.check("fixture", 3);
                    accepted.incrementAndGet();
                  } catch (ApiException ex) {
                    assertThat(ex.status()).isEqualTo(429);
                  }
                }));
      for (var job : jobs) job.get(10, TimeUnit.SECONDS);
      assertThat(accepted.get()).isEqualTo(3);
    } finally {
      executor.shutdownNow();
    }
  }
}
