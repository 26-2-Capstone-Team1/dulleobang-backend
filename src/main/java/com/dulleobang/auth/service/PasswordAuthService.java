package com.dulleobang.auth.service;

import com.dulleobang.auth.dto.TokenPair;
import com.dulleobang.auth.repository.CredentialRepository;
import com.dulleobang.common.exception.ApiException;
import com.dulleobang.user.domain.UserAccount;
import com.dulleobang.user.repository.UserAccountRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PasswordAuthService {
  private final CredentialRepository credentials;
  private final UserAccountRepository users;
  private final PasswordEncoder passwords;
  private final SessionService sessions;
  private final Clock clock;
  private final String dummy;

  public PasswordAuthService(
      CredentialRepository credentials,
      UserAccountRepository users,
      PasswordEncoder passwords,
      SessionService sessions,
      Clock clock) {
    this.credentials = credentials;
    this.users = users;
    this.passwords = passwords;
    this.sessions = sessions;
    this.clock = clock;
    dummy = passwords.encode(UUID.randomUUID().toString());
  }

  @Transactional
  public UserAccount signup(JsonNode input) {
    UUID user = UUID.randomUUID();
    String email = input.path("email").asText();
    var created =
        users.create(user, email, input.path("displayName").asText(), "EMAIL", clock.instant());
    if (!credentials.insert(user, email, passwords.encode(input.path("password").asText())))
      throw new ApiException(409, "EMAIL_ALREADY_EXISTS");
    return created;
  }

  @Transactional
  public TokenPair login(JsonNode input) {
    var credential = credentials.find(input.path("email").asText());
    String password = input.path("password").asText();
    boolean valid =
        passwords.matches(password, credential.map(c -> c.passwordHash()).orElse(dummy));
    if (!valid || credential.isEmpty()) throw new ApiException(401, "INVALID_CREDENTIALS");
    var account = credential.orElseThrow();
    if (passwords.upgradeEncoding(account.passwordHash()))
      credentials.rehash(account.userId(), passwords.encode(password));
    return sessions.create(account.userId());
  }
}
