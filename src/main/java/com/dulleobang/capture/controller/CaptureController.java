package com.dulleobang.capture.controller;

import com.dulleobang.capture.service.*;
import com.dulleobang.common.contract.*;
import com.dulleobang.common.idempotency.service.IdempotencyService;
import com.dulleobang.common.json.StrictJson;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class CaptureController {
  private final CaptureSubmissionService submission;
  private final CaptureQueryService query;
  private final JsonInput input;

  public CaptureController(
      CaptureSubmissionService submission, CaptureQueryService query, JsonInput input) {
    this.submission = submission;
    this.query = query;
    this.input = input;
  }

  @PostMapping(
      value = {"/api/listings/{id}/captures", "/api/furniture/{id}/captures"},
      consumes = "application/json")
  public ResponseEntity<String> submit(
      Principal actor,
      HttpServletRequest request,
      @PathVariable UUID id,
      @RequestBody(required = false) byte[] body,
      @RequestHeader(value = "Content-Type", required = false) String type,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    IdempotencyService.requireKey(key);
    String target = request.getRequestURI().contains("/listings/") ? "LISTING" : "FURNITURE";
    return HttpResponses.stored(
        submission.submit(
            UUID.fromString(actor.getName()),
            target,
            id,
            UUID.fromString(
                input.read(body, type, "CaptureSubmit").path("uploadSessionId").asText()),
            "/api/v1" + request.getRequestURI().substring("/api".length()),
            key));
  }

  @GetMapping("/api/captures/{id}")
  public ResponseEntity<String> get(Principal actor, @PathVariable UUID id) {
    return ResponseEntity.ok()
        .header("Content-Type", "application/json")
        .body(StrictJson.stringify(query.get(UUID.fromString(actor.getName()), id)));
  }
}
