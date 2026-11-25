package com.dulleobang.upload.service;

import com.dulleobang.common.config.BackendProperties;
import com.dulleobang.common.exception.ApiException;
import com.dulleobang.common.json.ApiTime;
import com.dulleobang.common.security.TokenVerifier;
import com.dulleobang.upload.domain.AssetFile;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.*;
import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class SignedUrlService {
  private final BackendProperties properties;
  private final TokenVerifier verifier;
  private final Clock clock;
  private final String base;

  public SignedUrlService(
      BackendProperties properties,
      TokenVerifier verifier,
      Clock clock,
      @Value("${BASE_URL:http://127.0.0.1:8081}") String base) {
    this.properties = properties;
    this.verifier = verifier;
    this.clock = clock;
    this.base = base.replaceAll("/$", "");
  }

  public Map<String, Object> grant(AssetFile file, String method) {
    var expires = clock.instant().plusSeconds(900);
    String token = sign(file, method, expires);
    var result = new LinkedHashMap<String, Object>();
    result.put("clientFileId", file.clientId());
    result.put("url", base + "/storage/" + file.id() + "?token=" + token);
    result.put("method", method);
    result.put("expiresAt", ApiTime.format(expires));
    if (method.equals("GET")) result.put("asset", asset(file));
    else {
      result.put("fileId", file.id());
      result.put(
          "headers",
          Map.of("Content-Type", file.contentType(), "x-amz-checksum-sha256", checksum(file)));
    }
    return result;
  }

  public String token(AssetFile file, String method) {
    return sign(file, method, clock.instant().plusSeconds(900));
  }

  private String sign(AssetFile file, String method, java.time.Instant expires) {
    try {
      var claims =
          new JWTClaimsSet.Builder()
              .issuer("dulleobang-storage")
              .audience("storage")
              .expirationTime(Date.from(expires))
              .claim("file", file.id().toString())
              .claim("method", method)
              .claim("sha", file.sha256())
              .build();
      var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
      jwt.sign(new MACSigner(properties.storageSecret()));
      return jwt.serialize();
    } catch (JOSEException ex) {
      throw new IllegalStateException(ex);
    }
  }

  public void verify(AssetFile file, String token, String method) {
    try {
      var claims = verifier.verify(token, "storage");
      if (!claims.getStringClaim("file").equals(file.id().toString())
          || !claims.getStringClaim("method").equals(method)
          || !claims.getStringClaim("sha").equals(file.sha256()))
        throw new IllegalArgumentException();
    } catch (Exception ex) {
      throw new ApiException(403, "INVALID_STORAGE_GRANT");
    }
  }

  public static String checksum(AssetFile file) {
    return Base64.getEncoder().encodeToString(HexFormat.of().parseHex(file.sha256()));
  }

  public Map<String, Object> asset(AssetFile file) {
    return Map.of(
        "fileId",
        file.id(),
        "contentType",
        file.contentType(),
        "byteSize",
        file.byteSize(),
        "sha256",
        file.sha256(),
        "format",
        file.format());
  }
}
