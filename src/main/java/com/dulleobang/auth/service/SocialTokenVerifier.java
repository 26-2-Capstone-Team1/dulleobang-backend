package com.dulleobang.auth.service;

import com.dulleobang.auth.domain.SocialIdentity;

public interface SocialTokenVerifier {
  String provider();

  SocialIdentity verify(String token);
}
