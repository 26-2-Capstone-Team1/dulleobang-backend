package com.dulleobang.auth.controller;

import com.dulleobang.auth.dto.TokenPair;
import com.dulleobang.auth.service.PasswordAuthService;
import com.dulleobang.common.contract.JsonInput;
import com.dulleobang.user.dto.UserResponse;
import com.dulleobang.user.mapper.UserMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping({"/api/auth", "/api/v1/auth"})
public class PasswordAuthController {
  private final PasswordAuthService auth;
  private final JsonInput input;

  public PasswordAuthController(PasswordAuthService auth, JsonInput input) {
    this.auth = auth;
    this.input = input;
  }

  @PostMapping("/signup")
  public ResponseEntity<UserResponse> signup(
      @RequestBody(required = false) byte[] body,
      @RequestHeader(value = "Content-Type", required = false) String type) {
    var user = auth.signup(input.read(body, type, "SignupRequest"));
    return ResponseEntity.status(201)
        .eTag("\"" + user.version() + "\"")
        .body(UserMapper.response(user));
  }

  @PostMapping("/login")
  public TokenPair login(
      @RequestBody(required = false) byte[] body,
      @RequestHeader(value = "Content-Type", required = false) String type) {
    return auth.login(input.read(body, type, "LoginRequest"));
  }
}
