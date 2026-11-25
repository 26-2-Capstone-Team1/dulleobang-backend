package com.dulleobang.verification.controller;

import com.dulleobang.common.contract.HttpResponses;
import com.dulleobang.common.idempotency.service.IdempotencyService;
import com.dulleobang.upload.controller.MultipartInput;
import com.dulleobang.verification.service.MobileAgentApplicationService;
import java.security.Principal;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartHttpServletRequest;

@RestController
public class MobileAgentApplicationController {
  private final MobileAgentApplicationService agents;
  private final MultipartInput input;

  public MobileAgentApplicationController(
      MobileAgentApplicationService agents, MultipartInput input) {
    this.agents = agents;
    this.input = input;
  }

  @PostMapping(value = "/api/users/me/agent-profile", consumes = "multipart/form-data")
  public ResponseEntity<String> apply(
      Principal actor,
      MultipartHttpServletRequest request,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    IdempotencyService.requireKey(key);
    return HttpResponses.stored(
        agents.apply(
            UUID.fromString(actor.getName()),
            request.getRequestURI(),
            key,
            input.files(request, "document", 1),
            input.fields(request, Set.of("name", "licenseNumber", "officeName", "documentName"))));
  }
}
