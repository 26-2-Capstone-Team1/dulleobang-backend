package com.dulleobang;

import static org.assertj.core.api.Assertions.*;

import com.dulleobang.common.exception.ApiException;
import com.dulleobang.infrastructure.oauth.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OAuthProviderTest {
  final Instant now = Instant.parse("2026-10-16T00:00:00Z");
  final Clock clock = Clock.fixed(now, ZoneOffset.UTC);

  String signed(RSAKey key, String issuer, String audience, int expiry) throws Exception {
    var claims =
        new JWTClaimsSet.Builder()
            .issuer(issuer)
            .audience(audience)
            .subject("subject-1")
            .issueTime(Date.from(now.minusSeconds(10)))
            .expirationTime(Date.from(now.plusSeconds(expiry)))
            .claim("email", "person@example.com")
            .claim("email_verified", true)
            .claim("name", "Person")
            .build();
    var jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
    jwt.sign(new RSASSASigner(key));
    return jwt.serialize();
  }

  @Test
  void checksGoogleClaims() throws Exception {
    var key = new RSAKeyGenerator(2048).keyID("one").generate();
    var wrong = new RSAKeyGenerator(2048).keyID("one").generate();
    var calls = new AtomicInteger();
    ProviderHttp http =
        (url, headers) -> {
          assertThat(url.toString()).isEqualTo("https://www.googleapis.com/oauth2/v3/certs");
          calls.incrementAndGet();
          return new ProviderHttp.Reply(200, new JWKSet(key.toPublicJWK()).toString());
        };
    var verifier = new GoogleTokenVerifier(http, "client-1, client-2", clock);
    var identity = verifier.verify(signed(key, "https://accounts.google.com", "client-1", 300));
    assertThat(identity.subject()).isEqualTo("subject-1");
    verifier.verify(signed(key, "accounts.google.com", "client-2", 300));
    assertThat(calls.get()).isEqualTo(1);
    for (String token :
        List.of(
            signed(wrong, "accounts.google.com", "client-1", 300),
            signed(key, "evil.example", "client-1", 300),
            signed(key, "accounts.google.com", "wrong", 300),
            signed(key, "accounts.google.com", "client-1", -1)))
      assertThatThrownBy(() -> verifier.verify(token))
          .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(401));
  }

  @Test
  void refreshesGoogleKeys() throws Exception {
    var key = new RSAKeyGenerator(2048).keyID("new-key").generate();
    var calls = new AtomicInteger();
    ProviderHttp http =
        (url, headers) ->
            new ProviderHttp.Reply(
                200,
                calls.incrementAndGet() == 1
                    ? "{\"keys\":[]}"
                    : new JWKSet(key.toPublicJWK()).toString());
    var verifier = new GoogleTokenVerifier(http, "client-1", clock);
    String token = signed(key, "accounts.google.com", "client-1", 300);
    assertThatThrownBy(() -> verifier.verify(token)).isInstanceOf(ApiException.class);
    assertThat(verifier.verify(token).provider()).isEqualTo("GOOGLE");
    assertThat(calls.get()).isEqualTo(2);
    var down =
        new GoogleTokenVerifier((url, h) -> new ProviderHttp.Reply(503, ""), "client-1", clock);
    assertThatThrownBy(() -> down.verify(token))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(503));
  }

  @Test
  void checksKakaoClaims() {
    ProviderHttp http =
        (url, h) -> {
          assertThat(h).containsEntry("Authorization", "Bearer sdk-token");
          return new ProviderHttp.Reply(
              200,
              url.getPath().contains("access_token_info")
                  ? "{\"id\":123,\"app_id\":100,\"expires_in\":300}"
                  : "{\"id\":123,\"kakao_account\":{\"profile\":{\"nickname\":\"Kakao\"}}}");
        };
    var verifier = new KakaoTokenVerifier(http, "100");
    assertThat(verifier.verify("sdk-token").subject()).isEqualTo("123");
    assertThatThrownBy(() -> new KakaoTokenVerifier(http, "999").verify("sdk-token"))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(401));
    var mismatch =
        new KakaoTokenVerifier(
            (url, h) ->
                new ProviderHttp.Reply(
                    200,
                    url.getPath().contains("access_token_info")
                        ? "{\"id\":123,\"app_id\":100,\"expires_in\":300}"
                        : "{\"id\":456}"),
            "100");
    assertThatThrownBy(() -> mismatch.verify("sdk-token")).isInstanceOf(ApiException.class);
  }
}
