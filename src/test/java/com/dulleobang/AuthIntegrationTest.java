package com.dulleobang;

import static org.assertj.core.api.Assertions.*;

import com.dulleobang.auth.domain.SocialIdentity;
import com.dulleobang.auth.service.SocialAccountService;
import com.dulleobang.common.contract.ContractValidator;
import com.dulleobang.common.json.StrictJson;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AuthIntegrationTest extends IntegrationTestSupport {
  @Autowired ContractValidator contracts;
  @Autowired SocialAccountService social;

  JsonNode createAndLogin(String prefix) throws Exception {
    var signup =
        json(
            "POST",
            prefix + "/auth/signup",
            Map.of(
                "email",
                "Auth@Example.com ",
                "password",
                "  PasswordNotTrimmed123!  ",
                "displayName",
                " 이름 "),
            null);
    assertThat(signup.statusCode()).as(signup.body()).isEqualTo(201);
    contracts.validate("User", signup.body());
    var login =
        json(
            "POST",
            prefix + "/auth/login",
            Map.of("email", "auth@example.com", "password", "  PasswordNotTrimmed123!  "),
            null);
    assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
    contracts.validate("TokenPair", login.body());
    return StrictJson.parse(login.body());
  }

  @Test
  void sharesAccounts() throws Exception {
    var pair = createAndLogin("/api");
    var user = pair.path("user");
    assertThat(user.path("displayName").asText()).isEqualTo("이름");
    assertThat(user.path("email").asText()).isEqualTo("auth@example.com");
    assertThat(user.path("roles").toString()).isEqualTo("[\"TENANT\"]");
    var me = json("GET", "/api/v1/users/me", null, pair.path("accessToken").asText());
    assertThat(me.statusCode()).isEqualTo(200);
    assertThat(StrictJson.parse(me.body())).isEqualTo(user);
    assertThat(me.headers().firstValue("ETag")).contains("\"1\"");
    var duplicate =
        json(
            "POST",
            "/api/v1/auth/signup",
            Map.of(
                "email", "AUTH@example.com", "password", "SomePassword123!", "displayName", "dup"),
            null);
    assertThat(duplicate.statusCode()).isEqualTo(409);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM credentials", Integer.class)).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE email='auth@example.com'", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void rejectsInvalidLogin() throws Exception {
    createAndLogin("/api/v1");
    for (String email : List.of("auth@example.com", "missing@example.com")) {
      var denied =
          json("POST", "/api/auth/login", Map.of("email", email, "password", "wrong"), null);
      assertThat(denied.statusCode()).isEqualTo(401);
      assertThat(StrictJson.parse(denied.body()).path("code").asText())
          .isEqualTo("INVALID_CREDENTIALS");
    }
    var escalation =
        json(
            "POST",
            "/api/auth/signup",
            Map.of(
                "email",
                "x@example.com",
                "password",
                "SomePassword123!",
                "displayName",
                "x",
                "roles",
                List.of("LISTING_AGENT")),
            null);
    assertThat(escalation.statusCode()).isEqualTo(422);
  }

  @Test
  void pythonArgonCredentialLogsInOverHttp() throws Exception {
    jdbc.update(
        "INSERT INTO credentials VALUES(?,?,?)",
        owner,
        "legacy@example.com",
        CompatibilityTest.fixture.path("passwordHash").asText());
    var login =
        json(
            "POST",
            "/api/v1/auth/login",
            Map.of(
                "email",
                "legacy@example.com",
                "password",
                CompatibilityTest.fixture.path("password").asText()),
            null);
    assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
    assertThat(StrictJson.parse(login.body()).path("user").path("userId").asText())
        .isEqualTo(owner.toString());
  }

  @Test
  void checksProfileUpdates() throws Exception {
    String token = createAndLogin("/api").path("accessToken").asText();
    assertThat(json("PATCH", "/api/v1/users/me", Map.of("displayName", "변경"), token).statusCode())
        .isEqualTo(428);
    var changed =
        json(
            "PATCH",
            "/api/v1/users/me",
            Map.of("displayName", "변경", "phone", "010-1234-5678", "isPhonePublic", true),
            token,
            "If-Match",
            "\"1\"");
    assertThat(changed.statusCode()).as(changed.body()).isEqualTo(200);
    assertThat(changed.headers().firstValue("ETag")).contains("\"2\"");
    contracts.validate("User", changed.body());
    assertThat(
            json(
                    "PATCH",
                    "/api/v1/users/me",
                    Map.of("displayName", "stale"),
                    token,
                    "If-Match",
                    "\"1\"")
                .statusCode())
        .isEqualTo(412);
    assertThat(
            json(
                    "PATCH",
                    "/api/v1/users/me",
                    Map.of("roles", List.of("LISTING_AGENT")),
                    token,
                    "If-Match",
                    "\"2\"")
                .statusCode())
        .isEqualTo(422);
  }

  @Test
  void checksRefreshReplay() throws Exception {
    var first = createAndLogin("/api");
    String refresh = first.path("refreshToken").asText(), key = UUID.randomUUID().toString();
    var rotated =
        json(
            "POST",
            "/api/auth/refresh",
            Map.of("refreshToken", refresh),
            null,
            "Idempotency-Key",
            key);
    assertThat(rotated.statusCode()).as(rotated.body()).isEqualTo(200);
    contracts.validate("TokenPair", rotated.body());
    var replay =
        json(
            "POST",
            "/api/auth/refresh",
            Map.of("refreshToken", refresh),
            null,
            "Idempotency-Key",
            key);
    assertThat(replay.body()).isEqualTo(rotated.body());
    var reuse =
        json(
            "POST",
            "/api/auth/refresh",
            Map.of("refreshToken", refresh),
            null,
            "Idempotency-Key",
            UUID.randomUUID().toString());
    assertThat(reuse.statusCode()).isEqualTo(401);
    assertThat(StrictJson.parse(reuse.body()).path("code").asText()).isEqualTo("TOKEN_REUSED");
    String access = StrictJson.parse(rotated.body()).path("accessToken").asText();
    assertThat(json("GET", "/api/users/me", null, access).statusCode()).isEqualTo(401);
    assertThat(
            json(
                    "POST",
                    "/api/auth/refresh",
                    Map.of("refreshToken", refresh),
                    null,
                    "Idempotency-Key",
                    key)
                .statusCode())
        .isEqualTo(401);
    assertThat(json("POST", "/api/auth/logout", Map.of("refreshToken", refresh), null).statusCode())
        .isEqualTo(204);
  }

  @Test
  void rotatesRefreshOnce() throws Exception {
    var first = createAndLogin("/api/v1");
    String refresh = first.path("refreshToken").asText(), key = UUID.randomUUID().toString();
    try (var pool = Executors.newFixedThreadPool(4)) {
      var futures = new ArrayList<Future<HttpResponse<String>>>();
      for (int n = 0; n < 4; n++)
        futures.add(
            pool.submit(
                () ->
                    json(
                        "POST",
                        "/api/v1/auth/refresh",
                        Map.of("refreshToken", refresh),
                        null,
                        "Idempotency-Key",
                        key)));
      var responses = new ArrayList<String>();
      for (var f : futures) {
        var r = f.get(20, TimeUnit.SECONDS);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        responses.add(r.body());
      }
      assertThat(new HashSet<>(responses)).hasSize(1);
    }
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_tokens", Integer.class))
        .isEqualTo(2);
  }

  @Test
  void revokesReusedRefresh() throws Exception {
    var first = createAndLogin("/api");
    String refresh = first.path("refreshToken").asText();
    try (var pool = Executors.newFixedThreadPool(2)) {
      var a =
          pool.submit(
              () ->
                  json(
                      "POST",
                      "/api/auth/refresh",
                      Map.of("refreshToken", refresh),
                      null,
                      "Idempotency-Key",
                      UUID.randomUUID().toString()));
      var b =
          pool.submit(
              () ->
                  json(
                      "POST",
                      "/api/auth/refresh",
                      Map.of("refreshToken", refresh),
                      null,
                      "Idempotency-Key",
                      UUID.randomUUID().toString()));
      assertThat(List.of(a.get().statusCode(), b.get().statusCode()))
          .containsExactlyInAnyOrder(200, 401);
    }
    assertThat(jdbc.queryForObject("SELECT bool_and(revoked) FROM sessions", Boolean.class))
        .isTrue();
  }

  @Test
  void checksLogout() throws Exception {
    var pair = createAndLogin("/api");
    String refresh = pair.path("refreshToken").asText();
    assertThat(json("POST", "/api/auth/logout", Map.of("refreshToken", refresh), null).statusCode())
        .isEqualTo(204);
    assertThat(json("GET", "/api/users/me", null, pair.path("accessToken").asText()).statusCode())
        .isEqualTo(401);
    assertThat(
            json("POST", "/api/auth/logout", Map.of("refreshToken", "x".repeat(64)), null)
                .statusCode())
        .isEqualTo(204);
    assertThat(
            json(
                    "POST",
                    "/api/auth/refresh",
                    Map.of("refreshToken", refresh),
                    null,
                    "Idempotency-Key",
                    UUID.randomUUID().toString())
                .statusCode())
        .isEqualTo(401);
  }

  @Test
  void checksAuthErrors() throws Exception {
    assertThat(
            json("POST", "/api/auth/refresh", Map.of("refreshToken", "x".repeat(64)), null)
                .statusCode())
        .isEqualTo(400);
    for (String provider : List.of("kakao", "google")) {
      var r =
          json(
              "POST",
              "/api/auth/oauth/" + provider,
              Map.of(provider.equals("kakao") ? "accessToken" : "idToken", "fake"),
              null);
      assertThat(r.statusCode()).isEqualTo(503);
      assertThat(StrictJson.parse(r.body()).path("code").asText())
          .isEqualTo("OAUTH_NOT_CONFIGURED");
    }
    var raw =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/signup"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"email\":1,\"email\":2}"))
            .build();
    assertThat(http.send(raw, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);
  }

  @Test
  void checksSocialIdentity() throws Exception {
    var local = createAndLogin("/api");
    var kakao = social.login(new SocialIdentity("KAKAO", "123", "auth@example.com", "Social"));
    var again =
        social.login(new SocialIdentity("KAKAO", "123", "different@example.com", "Changed"));
    var google = social.login(new SocialIdentity("GOOGLE", "123", "auth@example.com", "Social"));
    assertThat(kakao.user().userId())
        .isEqualTo(again.user().userId())
        .isNotEqualTo(google.user().userId())
        .isNotEqualTo(UUID.fromString(local.path("user").path("userId").asText()));
    assertThat(kakao.user().roles()).containsExactly("TENANT");
  }

  @Test
  void deduplicatesLogin() throws Exception {
    try (var pool = Executors.newFixedThreadPool(4)) {
      var futures = new ArrayList<Future<UUID>>();
      for (int n = 0; n < 4; n++)
        futures.add(
            pool.submit(
                () ->
                    social
                        .login(new SocialIdentity("GOOGLE", "same-sub", null, "Name"))
                        .user()
                        .userId()));
      Set<UUID> ids = new HashSet<>();
      for (var f : futures) ids.add(f.get(20, TimeUnit.SECONDS));
      assertThat(ids).hasSize(1);
    }
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM oauth_identities", Integer.class))
        .isEqualTo(1);
  }
}
