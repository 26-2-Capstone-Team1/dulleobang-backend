package com.dulleobang.auth.service;

import com.dulleobang.auth.domain.SocialIdentity;
import com.dulleobang.auth.dto.TokenPair;
import com.dulleobang.auth.repository.SocialIdentityRepository;
import com.dulleobang.user.repository.UserAccountRepository;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SocialAccountService {
  private static final String KAKAO_DEFAULT_DISPLAY_NAME = "카카오 사용자";

  private final SocialIdentityRepository identities;
  private final UserAccountRepository users;
  private final SessionService sessions;
  private final Clock clock;

  public SocialAccountService(
      SocialIdentityRepository identities,
      UserAccountRepository users,
      SessionService sessions,
      Clock clock) {
    this.identities = identities;
    this.users = users;
    this.sessions = sessions;
    this.clock = clock;
  }

  @Transactional
  public TokenPair login(SocialIdentity identity) {
    identities.lock(identity.provider(), identity.subject());
    UUID id = identities.find(identity.provider(), identity.subject()).orElse(null);
    String name = normalizeDisplayName(identity.displayName());
    if (id == null) {
      id = create(identity, name);
    } else if (identity.provider().equals("KAKAO")
        && name != null
        && !KAKAO_DEFAULT_DISPLAY_NAME.equals(name)) {
      users.replaceDisplayName(id, KAKAO_DEFAULT_DISPLAY_NAME, name);
    }
    return sessions.create(id);
  }

  private UUID create(SocialIdentity identity, String name) {
    UUID id = UUID.randomUUID();
    if (name == null)
      name = identity.provider().equals("KAKAO") ? KAKAO_DEFAULT_DISPLAY_NAME : "구글 사용자";
    String email =
        identity.email() == null ? null : identity.email().strip().toLowerCase(Locale.ROOT);
    if (email != null && (email.length() > 254 || !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")))
      email = null;
    users.create(id, email, name, identity.provider(), clock.instant());
    identities.insert(identity.provider(), identity.subject(), id);
    return id;
  }

  private static String normalizeDisplayName(String name) {
    if (name == null || name.isBlank()) return null;
    String trimmed = name.strip();
    int length = Math.min(40, trimmed.codePointCount(0, trimmed.length()));
    return trimmed.substring(0, trimmed.offsetByCodePoints(0, length));
  }
}
