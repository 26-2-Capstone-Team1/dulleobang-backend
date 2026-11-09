package com.dulleobang.auth.service;

import com.dulleobang.common.config.BackendProperties;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class JwtTokenIssuer {
  private final BackendProperties properties;
  private final Clock clock;

  public JwtTokenIssuer(BackendProperties properties, Clock clock) {
    this.properties = properties;
    this.clock = clock;
  }

  public String access(UUID user, UUID session) {
    try {
      var now = clock.instant();
      var claims =
          new JWTClaimsSet.Builder()
              .issuer("dulleobang")
              .audience("dulleobang-mobile")
              .subject(user.toString())
              .claim("sid", session.toString())
              .issueTime(Date.from(now))
              .expirationTime(Date.from(now.plusSeconds(900)))
              .build();
      var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
      jwt.sign(new MACSigner(properties.jwtSecret().getBytes(StandardCharsets.UTF_8)));
      return jwt.serialize();
    } catch (JOSEException ex) {
      throw new IllegalStateException("JWT signing failed", ex);
    }
  }
}
