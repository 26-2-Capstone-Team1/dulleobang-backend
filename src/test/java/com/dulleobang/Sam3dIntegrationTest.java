package com.dulleobang;

import static org.assertj.core.api.Assertions.*;

import com.dulleobang.common.json.StrictJson;
import com.dulleobang.sam3d.validation.BoxMesh;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Synthetic HTTP boundary fixture; does not run or claim GPU inference. */
class Sam3dIntegrationTest extends IntegrationTestSupport {
  static final String JOB = "1234567890abcdef1234567890abcdef";
  static final String PATH = "/api/v1/furniture/sam3d-imports";
  static final Map<String, Double> DIMS = Map.of("width", .6, "height", .8, "depth", .6);
  static final AtomicInteger calls = new AtomicInteger();
  static volatile String status = "succeeded";
  static volatile boolean verified = true, broken = false, offline = false, redirect = false;
  static final HttpServer studio = startStudio();

  static HttpServer startStudio() {
    try {
      var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/api/jobs/",
          exchange -> {
            calls.incrementAndGet();
            String path = exchange.getRequestURI().getPath();
            Object data =
                path.endsWith("generation.json")
                    ? Map.of(
                        "inference_verified",
                        verified,
                        "engine",
                        "SAM 3D Objects",
                        "coordinate_system",
                        "metres; Y-up; bottom at y=0",
                        "dimensions",
                        DIMS)
                    : Map.of(
                        "id",
                        JOB,
                        "status",
                        status,
                        "dimensions",
                        DIMS,
                        "model_url",
                        "http://169.254.169.254/private");
            byte[] bytes =
                path.endsWith("model.glb")
                    ? (broken
                        ? new byte[20]
                        : BoxMesh.create(StrictJson.parse(StrictJson.stringify(DIMS))))
                    : StrictJson.stringify(data).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (redirect)
              exchange.getResponseHeaders().add("Location", "http://169.254.169.254/private");
            exchange.sendResponseHeaders(offline ? 503 : redirect ? 302 : 200, bytes.length);
            try (var out = exchange.getResponseBody()) {
              out.write(bytes);
            }
          });
      server.start();
      Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
      return server;
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
  }

  @DynamicPropertySource
  static void sam3d(DynamicPropertyRegistry registry) {
    registry.add("dulleobang.sam3d.enabled", () -> "true");
    registry.add("dulleobang.sam3d.url", () -> "http://127.0.0.1:" + studio.getAddress().getPort());
  }

  @BeforeEach
  void sourceReset() {
    status = "succeeded";
    verified = true;
    broken = false;
    offline = false;
    redirect = false;
    calls.set(0);
  }

  Map<String, String> body() {
    return Map.of("studioJobId", JOB, "name", "온라인 의자", "category", "CHAIR");
  }

  HttpResponse<String> save(String token, String key) throws Exception {
    return json("POST", PATH, body(), token, "Idempotency-Key", key);
  }

  JsonNode ok(HttpResponse<String> response, int expected) {
    assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(expected);
    return StrictJson.parse(response.body());
  }

  @Test
  void importsIntoApp() throws Exception {
    String user = activeToken();
    var response = save(user, UUID.randomUUID().toString());
    var result = ok(response, 201);
    String id = result.path("furniture").path("furnitureId").asText();
    assertThat(result.path("source").path("collisionApproximation").asText())
        .isEqualTo("BOUNDING_BOX");
    var listing = ok(request("GET", "/api/users/me/furniture", user), 200);
    assertThat(listing.toString()).contains(id, "COMPLETED", "modelUri", "collisionMeshUri");
    var item = ok(request("GET", "/api/furniture/" + id, user), 200);
    assertThat(item.path("dimensions").path("height").asDouble()).isEqualTo(.8);
    URI asset = URI.create(item.path("modelUri").asText());
    var download =
        http.send(
            java.net.http.HttpRequest.newBuilder(
                    URI.create(
                        "http://127.0.0.1:"
                            + port
                            + asset.getRawPath()
                            + "?"
                            + asset.getRawQuery()))
                .GET()
                .build(),
            java.net.http.HttpResponse.BodyHandlers.ofByteArray());
    assertThat(download.statusCode()).isEqualTo(200);
    assertThat(download.body()).startsWith((byte) 'g', (byte) 'l', (byte) 'T', (byte) 'F');
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM furniture_models", Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM assets WHERE stored AND verified", Integer.class))
        .isEqualTo(2);
    assertThat(calls.get()).isEqualTo(3);
    UUID stranger = UUID.randomUUID();
    users.saveAndFlush(
        new com.dulleobang.user.domain.UserEntity(stranger, "다른 사람", java.time.Instant.now()));
    UUID session = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO sessions VALUES(?,?,?,FALSE)",
        session,
        stranger,
        java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(900)));
    assertThat(request("GET", "/api/furniture/" + id, token(stranger, session, false)).statusCode())
        .isEqualTo(404);
  }

  @Test
  void replaysWithoutUpstream() throws Exception {
    String user = activeToken(), key = UUID.randomUUID().toString();
    String id = ok(save(user, key), 201).path("furniture").path("furnitureId").asText();
    offline = true;
    assertThat(ok(save(user, key), 201).path("furniture").path("furnitureId").asText())
        .isEqualTo(id);
    assertThat(
            ok(save(user, UUID.randomUUID().toString()), 200)
                .path("furniture")
                .path("furnitureId")
                .asText())
        .isEqualTo(id);
    var changed = new HashMap<>(body());
    changed.put("name", "다른 이름");
    assertThat(json("POST", PATH, changed, user, "Idempotency-Key", key).statusCode())
        .isEqualTo(409);
    assertThat(
            json("POST", PATH, changed, user, "Idempotency-Key", UUID.randomUUID().toString())
                .statusCode())
        .isEqualTo(409);
    assertThat(calls.get()).isEqualTo(3);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM furniture", Integer.class)).isEqualTo(1);
  }

  @Test
  void validatesAuthentication() throws Exception {
    assertThat(save(null, UUID.randomUUID().toString()).statusCode()).isEqualTo(401);
    assertThat(json("POST", "/api/furniture/sam3d-imports", body(), null).statusCode())
        .isEqualTo(401);
    String user = activeToken();
    assertThat(json("POST", PATH, body(), user).statusCode()).isEqualTo(400);
    var bad = new HashMap<>(body());
    bad.put("studioJobId", "../secret");
    assertThat(
            json("POST", PATH, bad, user, "Idempotency-Key", UUID.randomUUID().toString())
                .statusCode())
        .isEqualTo(422);
    bad = new HashMap<>(body());
    bad.put("category", "INVALID");
    assertThat(
            json("POST", PATH, bad, user, "Idempotency-Key", UUID.randomUUID().toString())
                .statusCode())
        .isEqualTo(422);
    assertThat(calls.get()).isZero();
  }

  @Test
  void rejectsIncompleteResults() throws Exception {
    String user = activeToken();
    status = "running";
    ok(save(user, UUID.randomUUID().toString()), 409);
    status = "failed";
    ok(save(user, UUID.randomUUID().toString()), 422);
    status = "succeeded";
    verified = false;
    ok(save(user, UUID.randomUUID().toString()), 422);
    verified = true;
    broken = true;
    ok(save(user, UUID.randomUUID().toString()), 422);
    broken = false;
    redirect = true;
    ok(save(user, UUID.randomUUID().toString()), 502);
    redirect = false;
    offline = true;
    ok(save(user, UUID.randomUUID().toString()), 502);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM furniture", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM assets", Integer.class)).isZero();
  }

  @Test
  void preventsConcurrentCopies() throws Exception {
    String user = activeToken();
    try (var pool = Executors.newFixedThreadPool(2)) {
      var first = pool.submit(() -> save(user, UUID.randomUUID().toString()));
      var second = pool.submit(() -> save(user, UUID.randomUUID().toString()));
      assertThat(List.of(first.get().statusCode(), second.get().statusCode()))
          .containsExactlyInAnyOrder(201, 200);
    }
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM furniture", Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM furniture_models", Integer.class))
        .isEqualTo(1);
  }
}
