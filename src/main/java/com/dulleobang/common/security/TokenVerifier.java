package com.dulleobang.common.security;

import com.dulleobang.common.config.BackendProperties;
import com.dulleobang.common.exception.ApiException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import org.springframework.stereotype.Component;

@Component
public class TokenVerifier {
  private final BackendProperties properties;
  private final Clock clock;

  public TokenVerifier(BackendProperties properties, Clock clock) {
    this.properties = properties;
    this.clock = clock;
  }

  public JWTClaimsSet verify(String token, String kind) {
    try {
      boolean worker = kind.equals("worker"),
          storage = kind.equals("storage"),
          cursor = kind.equals("cursor");
      String key =
          worker
              ? properties.workerSecret()
              : (storage || cursor) ? properties.storageSecret() : properties.jwtSecret();
      String audience =
          worker
              ? "dulleobang-internal"
              : storage ? "storage" : cursor ? "cursor" : "dulleobang-mobile";
      SignedJWT jwt = SignedJWT.parse(token);
      if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm())
          || !jwt.verify(new MACVerifier(key.getBytes(StandardCharsets.UTF_8))))
        throw new Exception();
      JWTClaimsSet claims = jwt.getJWTClaimsSet();
      var now = clock.instant();
      if (claims.getExpirationTime() == null
          || !claims.getExpirationTime().toInstant().isAfter(now)
          || !claims.getAudience().contains(audience)) throw new Exception();
      if (claims.getNotBeforeTime() != null && claims.getNotBeforeTime().toInstant().isAfter(now))
        throw new Exception();
      if (!cursor) {
        String issuer =
            worker ? "dulleobang-worker" : storage ? "dulleobang-storage" : "dulleobang";
        if (!issuer.equals(claims.getIssuer())) throw new Exception();
      }
      if (!storage && !cursor) {
        if (claims.getIssueTime() == null
            || claims.getIssueTime().toInstant().isAfter(now)
            || claims.getSubject() == null
            || claims.getSubject().isBlank()) throw new Exception();
        if (worker && !"AI_WORKER".equals(claims.getStringClaim("scope"))) throw new Exception();
        if (!worker && claims.getStringClaim("sid") == null) throw new Exception();
      }
      if (storage
          && (claims.getStringClaim("file") == null
              || claims.getStringClaim("method") == null
              || claims.getStringClaim("sha") == null)) throw new Exception();
      if (cursor
          && (claims.getStringClaim("binding") == null
              || claims.getClaim("ids") == null
              || claims.getClaim("offset") == null)) throw new Exception();
      return claims;
    } catch (Exception ex) {
      throw new ApiException(401, "UNAUTHORIZED");
    }
  }
}
