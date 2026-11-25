package com.dulleobang.auth.service;

import com.dulleobang.auth.dto.TokenPair;
import com.dulleobang.auth.repository.RefreshRepository;
import com.dulleobang.common.crypto.Hashes;
import com.dulleobang.user.mapper.UserMapper;
import com.dulleobang.user.service.UserService;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class SessionService {
  public static final int REFRESH_SECONDS = 1209600;
  private final RefreshRepository repository;
  private final JwtTokenIssuer jwt;
  private final UserService users;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  public SessionService(
      RefreshRepository repository, JwtTokenIssuer jwt, UserService users, Clock clock) {
    this.repository = repository;
    this.jwt = jwt;
    this.users = users;
    this.clock = clock;
  }

  public TokenPair create(UUID user) {
    UUID session = UUID.randomUUID();
    repository.create(session, user, clock.instant().plusSeconds(REFRESH_SECONDS));
    return issue(user, session);
  }

  public TokenPair issue(UUID user, UUID session) {
    byte[] bytes = new byte[64];
    random.nextBytes(bytes);
    String refresh = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    repository.add(digest(refresh), session, clock.instant().plusSeconds(REFRESH_SECONDS));
    return new TokenPair(
        jwt.access(user, session),
        refresh,
        "Bearer",
        900,
        REFRESH_SECONDS,
        UserMapper.response(users.get(user)));
  }

  public static String digest(String token) {
    return Hashes.sha256(token.getBytes(StandardCharsets.UTF_8));
  }
}
