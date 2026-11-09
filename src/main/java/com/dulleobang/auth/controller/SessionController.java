package com.dulleobang.auth.controller;

import com.dulleobang.auth.service.RefreshTokenService;
import com.dulleobang.common.contract.*;
import com.dulleobang.common.idempotency.service.IdempotencyService;
import com.dulleobang.common.json.StrictJson;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping({"/api/auth", "/api/v1/auth"})
public class SessionController {
  private final RefreshTokenService sessions;
  private final JsonInput input;

  public SessionController(RefreshTokenService sessions, JsonInput input) {
    this.sessions = sessions;
    this.input = input;
  }

  @PostMapping("/refresh")
  public ResponseEntity<String> refresh(
      @RequestBody(required = false) byte[] body,
      @RequestHeader(value = "Content-Type", required = false) String type,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      org.springframework.web.context.request.NativeWebRequest request) {
    IdempotencyService.requireKey(key);
    var json = input.read(body, type, "RefreshRequest");
    String path =
        request.getNativeRequest(jakarta.servlet.http.HttpServletRequest.class).getRequestURI();
    return HttpResponses.stored(
        sessions.refresh(
            json.path("refreshToken").asText(), key, path, StrictJson.stringify(json)));
  }

  @PostMapping("/logout")
  public ResponseEntity<Void> logout(
      @RequestBody(required = false) byte[] body,
      @RequestHeader(value = "Content-Type", required = false) String type) {
    sessions.logout(input.read(body, type, "RefreshRequest").path("refreshToken").asText());
    return ResponseEntity.noContent().build();
  }
}
