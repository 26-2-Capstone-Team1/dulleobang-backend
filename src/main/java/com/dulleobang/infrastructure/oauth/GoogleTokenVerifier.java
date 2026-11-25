package com.dulleobang.infrastructure.oauth;

import com.dulleobang.auth.domain.SocialIdentity;
import com.dulleobang.auth.service.SocialTokenVerifier;
import com.dulleobang.common.exception.ApiException;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jwt.*;
import java.net.URI;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class GoogleTokenVerifier implements SocialTokenVerifier {
  private final ProviderHttp http;
  private final Set<String> clients;
  private final Clock clock;
  private JWKSet keys;
  private Instant expires = Instant.EPOCH;

  public GoogleTokenVerifier(
      ProviderHttp http, @Value("${GOOGLE_CLIENT_IDS:}") String clientIds, Clock clock) {
    this.http = http;
    this.clients =
        new HashSet<>(
            Arrays.stream(clientIds.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList());
    this.clock = clock;
  }

  public String provider() {
    return "GOOGLE";
  }

  public SocialIdentity verify(String token) {
    if (clients.isEmpty()) throw new ApiException(503, "OAUTH_NOT_CONFIGURED");
    try {
      var jwt = SignedJWT.parse(token);
      if (!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm())
          || jwt.getHeader().getKeyID() == null) throw invalid();
      var key = key(jwt.getHeader().getKeyID());
      if (!(key instanceof RSAKey rsa) || !jwt.verify(new RSASSAVerifier(rsa))) throw invalid();
      var c = jwt.getJWTClaimsSet();
      var now = clock.instant();
      if (!Set.of("accounts.google.com", "https://accounts.google.com").contains(c.getIssuer())
          || c.getAudience().stream().noneMatch(clients::contains)
          || c.getExpirationTime() == null
          || !c.getExpirationTime().toInstant().isAfter(now)
          || c.getIssueTime() == null
          || c.getIssueTime().toInstant().isAfter(now.plusSeconds(60))
          || (c.getNotBeforeTime() != null && c.getNotBeforeTime().toInstant().isAfter(now))
          || c.getSubject() == null
          || c.getSubject().isBlank()
          || c.getSubject().length() > 255) throw invalid();
      String azp = c.getStringClaim("azp");
      if ((c.getAudience().size() > 1 || azp != null) && !clients.contains(azp)) throw invalid();
      String email =
          Boolean.TRUE.equals(c.getBooleanClaim("email_verified"))
              ? c.getStringClaim("email")
              : null;
      return new SocialIdentity(provider(), c.getSubject(), email, c.getStringClaim("name"));
    } catch (java.text.ParseException
        | JOSEException
        | IllegalArgumentException
        | NullPointerException ex) {
      throw invalid();
    }
  }

  private synchronized JWK key(String kid) throws java.text.ParseException {
    boolean refreshed = false;
    if (keys == null || !expires.isAfter(clock.instant())) {
      reload();
      refreshed = true;
    }
    var key = keys.getKeyByKeyId(kid);
    if (key == null && !refreshed) {
      reload();
      key = keys.getKeyByKeyId(kid);
    }
    if (key == null) throw invalid();
    return key;
  }

  private void reload() {
    var reply = http.get(URI.create("https://www.googleapis.com/oauth2/v3/certs"), Map.of());
    if (reply.status() != 200) throw new ApiException(503, "OAUTH_PROVIDER_UNAVAILABLE");
    try {
      keys = JWKSet.parse(reply.body());
      expires = clock.instant().plusSeconds(3600);
    } catch (java.text.ParseException ex) {
      throw new ApiException(503, "OAUTH_PROVIDER_UNAVAILABLE");
    }
  }

  private ApiException invalid() {
    return new ApiException(401, "INVALID_OAUTH_TOKEN");
  }
}
