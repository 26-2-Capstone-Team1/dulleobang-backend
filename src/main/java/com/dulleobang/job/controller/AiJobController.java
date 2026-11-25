package com.dulleobang.job.controller;

import com.dulleobang.common.contract.HttpResponses;
import com.dulleobang.common.idempotency.service.IdempotencyService;
import com.dulleobang.job.mapper.JobMapper;
import com.dulleobang.job.service.*;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class AiJobController {
  private final JobAccessService access;
  private final JobCommandService commands;
  private final JobRetryService retries;

  public AiJobController(
      JobAccessService access, JobCommandService commands, JobRetryService retries) {
    this.access = access;
    this.commands = commands;
    this.retries = retries;
  }

  @GetMapping({"/api/ai-jobs/{id}", "/api/v1/ai-jobs/{id}"})
  public ResponseEntity<String> get(
      Principal actor, HttpServletRequest request, @PathVariable UUID id) {
    return HttpResponses.json(
        JobMapper.response(
            access.owned(UUID.fromString(actor.getName()), id, false),
            !request.getRequestURI().startsWith("/api/v1/")));
  }

  @PostMapping("/api/v1/ai-jobs/{id}/cancel")
  public ResponseEntity<String> cancel(Principal actor, @PathVariable UUID id) {
    return HttpResponses.json(commands.cancel(UUID.fromString(actor.getName()), id));
  }

  @PostMapping("/api/v1/ai-jobs/{id}/retries")
  public ResponseEntity<String> retry(
      Principal actor,
      HttpServletRequest request,
      @PathVariable UUID id,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    IdempotencyService.requireKey(key);
    return HttpResponses.stored(
        retries.retry(UUID.fromString(actor.getName()), id, request.getRequestURI(), key));
  }
}
