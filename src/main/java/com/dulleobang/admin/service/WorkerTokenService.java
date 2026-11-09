package com.dulleobang.admin.service;

import com.dulleobang.common.config.BackendProperties;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.*;
import java.time.Clock;
import java.util.Date;
import org.springframework.stereotype.Service;

@Service
public class WorkerTokenService {
  private final BackendProperties properties;
  private final Clock clock;

  public WorkerTokenService(BackendProperties properties, Clock clock) {
    this.properties = properties;
    this.clock = clock;
  }

  public String issue(String worker, int seconds) {
    if (worker == null
        || worker.isBlank()
        || worker.length() > 200
        || seconds < 1
        || seconds > 86400)
      throw new IllegalArgumentException("Invalid worker ID or lifetime (1..86400 seconds)");
    try {
      var now = clock.instant();
      var claims =
          new JWTClaimsSet.Builder()
              .issuer("dulleobang-worker")
              .audience("dulleobang-internal")
              .subject(worker)
              .claim("scope", "AI_WORKER")
              .issueTime(Date.from(now))
              .expirationTime(Date.from(now.plusSeconds(seconds)))
              .build();
      var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
      jwt.sign(new MACSigner(properties.workerSecret()));
      return jwt.serialize();
    } catch (JOSEException ex) {
      throw new IllegalStateException("Worker token signing failed", ex);
    }
  }
}
