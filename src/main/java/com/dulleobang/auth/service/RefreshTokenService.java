package com.dulleobang.auth.service;

import com.dulleobang.auth.domain.RefreshSession;
import com.dulleobang.auth.repository.RefreshRepository;
import com.dulleobang.common.exception.ApiException;
import com.dulleobang.common.idempotency.*;
import com.dulleobang.common.idempotency.service.IdempotencyService;
import com.dulleobang.common.json.StrictJson;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefreshTokenService {
  private final RefreshRepository repository;
  private final SessionService sessions;
  private final IdempotencyService idempotency;
  private final Clock clock;

  public RefreshTokenService(
      RefreshRepository repository,
      SessionService sessions,
      IdempotencyService idempotency,
      Clock clock) {
    this.repository = repository;
    this.sessions = sessions;
    this.idempotency = idempotency;
    this.clock = clock;
  }

  public StoredResponse refresh(String token, String key, String path, String json) {
    IdempotencyService.requireKey(key);
    String digest = SessionService.digest(token);
    UUID session = repository.session(digest).orElseThrow(() -> invalid());
    return idempotency.execute(
        "refresh:" + session,
        "POST",
        path,
        key,
        json,
        () -> authorize(session, digest),
        () -> rotate(session, digest));
  }

  private RefreshSession authorize(UUID session, String digest) {
    var state = repository.lock(session).orElseThrow(() -> invalid());
    if (state.revoked()
        || !state.expiresAt().isAfter(clock.instant())
        || !repository.valid(digest, session, clock.instant())) throw invalid();
    return state;
  }

  private StoredResponse rotate(UUID session, String digest) {
    var state = authorize(session, digest);
    if (!repository.consume(digest)) {
      repository.revoke(session);
      throw new CommittedFailure(new ApiException(401, "TOKEN_REUSED"));
    }
    repository.extend(session, clock.instant().plusSeconds(SessionService.REFRESH_SECONDS));
    return new StoredResponse(
        200, StrictJson.stringify(sessions.issue(state.userId(), session)), Map.of());
  }

  @Transactional
  public void logout(String token) {
    repository
        .session(SessionService.digest(token))
        .ifPresent(
            session -> {
              repository.lock(session);
              repository.revoke(session);
            });
  }

  private ApiException invalid() {
    return new ApiException(401, "INVALID_REFRESH_TOKEN");
  }
}
