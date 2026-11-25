package com.dulleobang.auth.service;

import com.dulleobang.auth.dto.TokenPair;
import com.dulleobang.common.exception.ApiException;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class SocialAuthService {
  private final List<SocialTokenVerifier> providers;
  private final SocialAccountService accounts;

  public SocialAuthService(List<SocialTokenVerifier> providers, SocialAccountService accounts) {
    this.providers = List.copyOf(providers);
    this.accounts = accounts;
  }

  public TokenPair login(String provider, String token) {
    return accounts.login(
        providers.stream()
            .filter(p -> p.provider().equals(provider))
            .findFirst()
            .orElseThrow(() -> new ApiException(503, "OAUTH_NOT_CONFIGURED"))
            .verify(token));
  }
}
