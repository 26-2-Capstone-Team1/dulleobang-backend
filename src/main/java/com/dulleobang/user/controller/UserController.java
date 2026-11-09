package com.dulleobang.user.controller;

import com.dulleobang.common.contract.JsonInput;
import com.dulleobang.user.domain.UserAccount;
import com.dulleobang.user.dto.UserResponse;
import com.dulleobang.user.mapper.UserMapper;
import com.dulleobang.user.service.UserService;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class UserController {
  private final UserService users;
  private final JsonInput input;

  public UserController(UserService users, JsonInput input) {
    this.users = users;
    this.input = input;
  }

  @GetMapping({"/api/users/me", "/api/v1/users/me"})
  public ResponseEntity<UserResponse> me(Principal actor) {
    return response(users.get(UUID.fromString(actor.getName())));
  }

  @PatchMapping("/api/v1/users/me")
  public ResponseEntity<UserResponse> patch(
      Principal actor,
      @RequestBody(required = false) byte[] body,
      @RequestHeader(value = "Content-Type", required = false) String type,
      @RequestHeader(value = "If-Match", required = false) String etag) {
    return response(
        users.patch(UUID.fromString(actor.getName()), etag, input.read(body, type, "UserPatch")));
  }

  private ResponseEntity<UserResponse> response(UserAccount user) {
    return ResponseEntity.ok().eTag("\"" + user.version() + "\"").body(UserMapper.response(user));
  }
}
