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
    UUID id =
        identities.find(identity.provider(), identity.subject()).orElseGet(() -> create(identity));
    return sessions.create(id);
  }

  private UUID create(SocialIdentity identity) {
    UUID id = UUID.randomUUID();
    String name = identity.displayName();
    if (name == null || name.isBlank())
      name = identity.provider().equals("KAKAO") ? "카카오 사용자" : "구글 사용자";
    name = name.strip();
    name =
        name.substring(
            0, name.offsetByCodePoints(0, Math.min(40, name.codePointCount(0, name.length()))));
    String email =
        identity.email() == null ? null : identity.email().strip().toLowerCase(Locale.ROOT);
    if (email != null && (email.length() > 254 || !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")))
      email = null;
    users.create(id, email, name, identity.provider(), clock.instant());
    identities.insert(identity.provider(), identity.subject(), id);
    return id;
  }
}
