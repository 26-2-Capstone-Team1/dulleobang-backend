package com.dulleobang;

import static org.assertj.core.api.Assertions.*;

import com.dulleobang.common.contract.ApiInventory;
import com.dulleobang.common.idempotency.*;
import com.dulleobang.common.idempotency.service.IdempotencyService;
import com.dulleobang.common.json.StrictJson;
import com.dulleobang.common.pagination.service.CursorService;
import com.dulleobang.common.ratelimit.service.RateLimitService;
import com.dulleobang.user.domain.UserEntity;
import com.dulleobang.user.repository.UserRepository;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.*;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "dulleobang.rate.enabled=false")
abstract class IntegrationTestSupport {
  static final String USER_KEY = "public-user-fixture-key-00000000000001";
  static final String WORKER_KEY = "public-worker-fixture-key-000000000001";
  static final String STORAGE_KEY = "public-storage-fixture-key-00000000001";
  static EmbeddedPostgres postgres;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) throws Exception {
    String url = System.getenv("TEST_DB_URL");
    if (url == null) {
      if (postgres == null) {
        postgres = EmbeddedPostgres.builder().setPort(0).start();
        Runtime.getRuntime()
            .addShutdownHook(
                new Thread(
                    () -> {
                      try {
                        postgres.close();
                      } catch (Exception ignored) {
                      }
                    }));
      }
      url = postgres.getJdbcUrl("postgres", "postgres");
    }
    String selected = url;
    registry.add("spring.datasource.url", () -> selected);
    registry.add(
        "spring.datasource.username",
        () -> System.getenv().getOrDefault("TEST_DB_USER", "postgres"));
    registry.add(
        "spring.datasource.password", () -> System.getenv().getOrDefault("TEST_DB_PASSWORD", ""));
    registry.add("dulleobang.jwt-secret", () -> USER_KEY);
    registry.add("dulleobang.worker-secret", () -> WORKER_KEY);
    registry.add("dulleobang.storage-secret", () -> STORAGE_KEY);
    registry.add("dulleobang.scheduler.enabled", () -> "false");
    registry.add("BASE_URL", () -> "https://storage.example.test");
    registry.add("dulleobang.cors-origins", () -> "http://localhost:3000");
  }

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired UserRepository users;
  @Autowired ApiInventory inventory;
  @Autowired IdempotencyService idempotency;
  @Autowired CursorService cursors;
  @Autowired RateLimitService rates;
  @Autowired ResponseCipher cipher;
  final HttpClient http = HttpClient.newHttpClient();
  UUID owner;

  @BeforeEach
  void reset() {
    jdbc.execute("TRUNCATE users CASCADE");
    jdbc.execute("TRUNCATE admin_reviews");
    jdbc.execute("DELETE FROM idempotency_records");
    jdbc.execute("DELETE FROM rate_limit_buckets");
    owner = UUID.randomUUID();
    users.saveAndFlush(new UserEntity(owner, "테스트", Instant.now()));
  }

  HttpResponse<String> request(String method, String path, String token) throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .method(method, HttpRequest.BodyPublishers.noBody());
    if (token != null) builder.header("Authorization", "Bearer " + token);
    return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  String token(UUID user, UUID session, boolean worker) throws Exception {
    var claims =
        new JWTClaimsSet.Builder()
            .issuer(worker ? "dulleobang-worker" : "dulleobang")
            .audience(worker ? "dulleobang-internal" : "dulleobang-mobile")
            .subject(worker ? "worker" : user.toString())
            .issueTime(new Date())
            .expirationTime(Date.from(Instant.now().plusSeconds(900)));
    if (worker) claims.claim("scope", "AI_WORKER");
    else claims.claim("sid", session.toString());
    var signed = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
    signed.sign(new MACSigner(worker ? WORKER_KEY : USER_KEY));
    return signed.serialize();
  }

  HttpResponse<String> json(
      String method, String path, Object body, String token, String... headers) throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header("Content-Type", "application/json")
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(StrictJson.stringify(body)));
    if (token != null) builder.header("Authorization", "Bearer " + token);
    for (int n = 0; n < headers.length; n += 2) builder.header(headers[n], headers[n + 1]);
    return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  String activeToken() throws Exception {
    UUID session = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO sessions VALUES(?,?,?,FALSE)",
        session,
        owner,
        java.sql.Timestamp.from(Instant.now().plusSeconds(900)));
    return token(owner, session, false);
  }
}
