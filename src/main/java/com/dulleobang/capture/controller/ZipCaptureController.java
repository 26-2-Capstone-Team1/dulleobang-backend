package com.dulleobang.capture.controller;

import com.dulleobang.capture.service.ZipCaptureService;
import com.dulleobang.common.contract.HttpResponses;
import com.dulleobang.common.idempotency.service.IdempotencyService;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class ZipCaptureController {
  private final ZipCaptureService captures;

  public ZipCaptureController(ZipCaptureService captures) {
    this.captures = captures;
  }

  @PostMapping(
      value = {
        "/api/listings/{id}/captures",
        "/api/furniture/{id}/captures",
        "/api/v1/listings/{id}/captures/zip"
      },
      consumes = "application/zip")
  public ResponseEntity<String> submit(
      Principal actor,
      HttpServletRequest request,
      @PathVariable UUID id,
      @RequestHeader(value = "Idempotency-Key", required = false) String key)
      throws java.io.IOException {
    IdempotencyService.requireKey(key);
    String type = request.getRequestURI().contains("/listings/") ? "LISTING" : "FURNITURE";
    return HttpResponses.stored(
        captures.submit(
            UUID.fromString(actor.getName()),
            type,
            id,
            request.getRequestURI(),
            key,
            request.getInputStream()));
  }
}
