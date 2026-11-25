package com.dulleobang.verification.controller;

import com.dulleobang.common.contract.*;
import com.dulleobang.common.idempotency.service.IdempotencyService;
import com.dulleobang.common.json.StrictJson;
import com.dulleobang.verification.service.AgentVerificationService;
import java.security.Principal;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class AgentVerificationController {
  private final AgentVerificationService agents;
  private final JsonInput input;

  public AgentVerificationController(AgentVerificationService agents, JsonInput input) {
    this.agents = agents;
    this.input = input;
  }

  @GetMapping("/api/users/me/agent-profile")
  public ResponseEntity<String> get(Principal actor) {
    return ResponseEntity.ok()
        .header("Content-Type", "application/json")
        .body(StrictJson.stringify(agents.get(UUID.fromString(actor.getName()), true)));
  }

  @PostMapping(value = "/api/users/me/agent-profile", consumes = "application/json")
  public ResponseEntity<String> create(
      Principal actor,
      @RequestBody(required = false) byte[] body,
      @RequestHeader(value = "Content-Type", required = false) String type,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    IdempotencyService.requireKey(key);
    return HttpResponses.stored(
        agents.create(
            UUID.fromString(actor.getName()),
            "/api/v1/users/me/listing-agent-verification",
            key,
            input.read(body, type, "ListingAgentVerificationCreate")));
  }
}
