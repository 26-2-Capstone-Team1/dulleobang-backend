package com.dulleobang.upload.controller;

import com.dulleobang.common.contract.*;
import com.dulleobang.common.contract.HttpResponses;
import com.dulleobang.common.idempotency.service.IdempotencyService;
import com.dulleobang.upload.service.UploadSessionService;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/upload-sessions")
public class UploadSessionController {
  private final UploadSessionService sessions;
  private final JsonInput input;

  public UploadSessionController(UploadSessionService sessions, JsonInput input) {
    this.sessions = sessions;
    this.input = input;
  }

  @PostMapping
  public ResponseEntity<String> create(
      Principal actor,
      @RequestBody(required = false) byte[] body,
      @RequestHeader(value = "Content-Type", required = false) String type,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    IdempotencyService.requireKey(key);
    return HttpResponses.stored(
        sessions.create(
            UUID.fromString(actor.getName()),
            "/api/v1/upload-sessions",
            key,
            input.read(body, type, "UploadSessionCreate")));
  }

  @GetMapping("/{id}")
  public ResponseEntity<String> get(Principal actor, @PathVariable UUID id) {
    return HttpResponses.json(sessions.get(UUID.fromString(actor.getName()), id));
  }

  @PostMapping("/{id}/upload-urls")
  public ResponseEntity<String> renew(
      Principal actor,
      @PathVariable UUID id,
      @RequestBody(required = false) byte[] body,
      @RequestHeader(value = "Content-Type", required = false) String type) {
    return HttpResponses.json(
        sessions.renew(
            UUID.fromString(actor.getName()), id, input.read(body, type, "RenewUploadsRequest")));
  }

  @PostMapping("/{id}/complete")
  public ResponseEntity<String> complete(Principal actor, @PathVariable UUID id) {
    return HttpResponses.json(sessions.complete(UUID.fromString(actor.getName()), id));
  }
}
