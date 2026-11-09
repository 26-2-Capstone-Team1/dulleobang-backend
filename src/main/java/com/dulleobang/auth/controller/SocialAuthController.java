package com.dulleobang.auth.controller;

import com.dulleobang.auth.dto.TokenPair;
import com.dulleobang.auth.service.SocialAuthService;
import com.dulleobang.common.contract.JsonInput;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping({"/api/auth", "/api/v1/auth"})
public class SocialAuthController {
  private final SocialAuthService auth;
  private final JsonInput input;

  public SocialAuthController(SocialAuthService auth, JsonInput input) {
    this.auth = auth;
    this.input = input;
  }

  @PostMapping("/oauth/kakao")
  public TokenPair kakao(
      @RequestBody(required = false) byte[] body,
      @RequestHeader(value = "Content-Type", required = false) String type) {
    return auth.login("KAKAO", input.read(body, type, "KakaoLogin").path("accessToken").asText());
  }

  @PostMapping("/oauth/google")
  public TokenPair google(
      @RequestBody(required = false) byte[] body,
      @RequestHeader(value = "Content-Type", required = false) String type) {
    return auth.login("GOOGLE", input.read(body, type, "GoogleLogin").path("idToken").asText());
  }
}
