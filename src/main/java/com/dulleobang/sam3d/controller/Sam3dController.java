package com.dulleobang.sam3d.controller;

import com.dulleobang.common.contract.*;
import com.dulleobang.common.idempotency.service.IdempotencyService;
import com.dulleobang.sam3d.service.Sam3dImportService;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class Sam3dController {
  private final Sam3dImportService imports;
  private final JsonInput input;

  public Sam3dController(Sam3dImportService imports, JsonInput input) {
    this.imports = imports;
    this.input = input;
  }

  @PostMapping({"/api/v1/furniture/sam3d-imports", "/api/furniture/sam3d-imports"})
  public ResponseEntity<String> save(
      Principal actor,
      HttpServletRequest request,
      @RequestBody(required = false) byte[] body,
      @RequestHeader(value = "Content-Type", required = false) String type,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    IdempotencyService.requireKey(key);
    return HttpResponses.stored(
        imports.save(
            UUID.fromString(actor.getName()),
            request.getRequestURI(),
            key,
            input.read(body, type, "Sam3dImport"),
            !request.getRequestURI().startsWith("/api/v1/")));
  }
}
