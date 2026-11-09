package com.dulleobang.common.pagination.service;

import com.dulleobang.common.config.BackendProperties;
import com.dulleobang.common.exception.ApiException;
import com.dulleobang.common.json.StrictJson;
import com.dulleobang.common.pagination.repository.CursorRepository;
import com.dulleobang.common.security.TokenVerifier;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.*;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CursorService {
  public record Page(List<String> ids, String nextCursor, boolean hasNext) {}

  private final CursorRepository repository;
  private final TokenVerifier verifier;
  private final BackendProperties properties;
  private final Clock clock;

  public CursorService(
      CursorRepository repository,
      TokenVerifier verifier,
      BackendProperties properties,
      Clock clock) {
    this.repository = repository;
    this.verifier = verifier;
    this.properties = properties;
    this.clock = clock;
  }

  @Transactional
  public Page start(
      UUID owner, String path, Map<String, Object> filters, List<String> ids, int limit) {
    if (new HashSet<>(ids).size() != ids.size())
      throw new IllegalArgumentException("Duplicate snapshot IDs");
    UUID id = UUID.randomUUID();
    String binding = binding(owner, path, filters);
    var expires = clock.instant().plusSeconds(1800);
    repository.save(id, owner, binding, StrictJson.stringify(ids), expires);
    return page(id, binding, ids, 0, limit, Date.from(expires));
  }

  public Page resume(
      UUID owner, String path, Map<String, Object> filters, String token, int limit) {
    return resume(owner, path, filters, token, limit, id -> true);
  }

  public Page resume(
      UUID owner,
      String path,
      Map<String, Object> filters,
      String token,
      int limit,
      java.util.function.Predicate<String> live) {
    try {
      var claims = verifier.verify(token, "cursor");
      String binding = binding(owner, path, filters);
      if (!binding.equals(claims.getStringClaim("binding"))) throw new IllegalArgumentException();
      UUID id = UUID.fromString(claims.getStringClaim("ids"));
      var snapshot = repository.get(id);
      if (snapshot == null
          || !snapshot.owner().equals(owner)
          || !snapshot.binding().equals(binding)
          || !snapshot.expires().isAfter(clock.instant())) throw new IllegalArgumentException();
      List<String> ids = new ArrayList<>();
      StrictJson.parse(snapshot.ids()).forEach(n -> ids.add(n.asText()));
      return page(
          id,
          binding,
          ids,
          claims.getIntegerClaim("offset"),
          limit,
          claims.getExpirationTime(),
          live);
    } catch (Exception ex) {
      throw new ApiException(400, "INVALID_CURSOR");
    }
  }

  private String binding(UUID owner, String path, Map<String, Object> filters) {
    return StrictJson.digest(
        StrictJson.stringify(Map.of("user", owner.toString(), "path", path, "filters", filters)));
  }

  private Page page(
      UUID id, String binding, List<String> ids, int offset, int limit, Date expires) {
    return page(id, binding, ids, offset, limit, expires, x -> true);
  }

  private Page page(
      UUID id,
      String binding,
      List<String> ids,
      int offset,
      int limit,
      Date expires,
      java.util.function.Predicate<String> live) {
    if (limit < 1 || limit > 200 || offset < 0 || offset > ids.size())
      throw new ApiException(400, "INVALID_QUERY_PARAMETER");
    var selected = new ArrayList<String>();
    int end = offset;
    while (end < ids.size() && selected.size() < limit) {
      String value = ids.get(end++);
      if (live.test(value)) selected.add(value);
    }
    while (end < ids.size() && !live.test(ids.get(end))) end++;
    String token = null;
    if (end < ids.size()) {
      try {
        var claims =
            new JWTClaimsSet.Builder()
                .audience("cursor")
                .expirationTime(expires)
                .claim("binding", binding)
                .claim("ids", id.toString())
                .claim("offset", end)
                .build();
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(properties.storageSecret()));
        token = jwt.serialize();
      } catch (Exception ex) {
        throw new IllegalStateException(ex);
      }
    }
    return new Page(List.copyOf(selected), token, end < ids.size());
  }
}
