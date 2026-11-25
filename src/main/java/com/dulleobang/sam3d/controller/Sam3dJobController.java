package com.dulleobang.sam3d.controller;

import com.dulleobang.common.contract.HttpResponses;
import com.dulleobang.common.contract.JsonInput;
import com.dulleobang.common.exception.ApiException;
import com.dulleobang.common.idempotency.service.IdempotencyService;
import com.dulleobang.sam3d.service.Sam3dGenerationService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(Sam3dJobController.PATH)
public class Sam3dJobController {
  static final String PATH = "/api/furniture/sam3d-jobs";
  private static final int MAX_REQUEST_BYTES = 10 * 1024 * 1024;
  private final Sam3dGenerationService generation;
  private final JsonInput input;

  public Sam3dJobController(Sam3dGenerationService generation, JsonInput input) {
    this.generation = generation;
    this.input = input;
  }

  @PostMapping
  public ResponseEntity<String> create(
      Principal actor,
      HttpServletRequest request,
      @RequestHeader(value = "Idempotency-Key", required = false) String key)
      throws IOException {
    IdempotencyService.requireKey(key);
    if (request.getContentLengthLong() > MAX_REQUEST_BYTES)
      throw new ApiException(413, "BODY_TOO_LARGE");
    byte[] bytes = request.getInputStream().readNBytes(MAX_REQUEST_BYTES + 1);
    var json = input.read(bytes, request.getContentType(), "Sam3dJobCreate");
    return HttpResponses.stored(
        generation.create(UUID.fromString(actor.getName()), PATH, key, json));
  }

  @GetMapping("/{id}")
  public ResponseEntity<String> status(Principal actor, @PathVariable String id) {
    return HttpResponses.json(generation.status(UUID.fromString(actor.getName()), id));
  }
}
