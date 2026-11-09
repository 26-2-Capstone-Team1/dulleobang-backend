package com.dulleobang.furniture.controller;

import com.dulleobang.common.contract.*;
import com.dulleobang.common.idempotency.service.IdempotencyService;
import com.dulleobang.furniture.mapper.FurnitureMapper;
import com.dulleobang.furniture.service.*;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class FurnitureController {
  private final FurnitureQueryService query;
  private final FurnitureCommandService command;
  private final JsonInput input;

  public FurnitureController(
      FurnitureQueryService query, FurnitureCommandService command, JsonInput input) {
    this.query = query;
    this.command = command;
    this.input = input;
  }

  @PostMapping({"/api/furniture", "/api/v1/furniture"})
  public ResponseEntity<String> create(
      Principal actor,
      HttpServletRequest request,
      @RequestBody(required = false) byte[] body,
      @RequestHeader(value = "Content-Type", required = false) String type,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    IdempotencyService.requireKey(key);
    boolean mobile = !request.getRequestURI().startsWith("/api/v1/");
    var json =
        mobile
            ? input.readMobile(body, type, "MobilecreateFurniture")
            : input.read(body, type, "FurnitureCreate");
    return HttpResponses.stored(
        command.create(
            UUID.fromString(actor.getName()), request.getRequestURI(), key, json, mobile));
  }

  @GetMapping({"/api/furniture/{id}", "/api/v1/furniture/{id}"})
  public ResponseEntity<String> get(
      Principal actor,
      HttpServletRequest request,
      @PathVariable UUID id,
      @RequestParam(required = false) Integer modelVersion) {
    if (modelVersion != null && modelVersion < 1)
      throw new com.dulleobang.common.exception.ApiException(422, "VALIDATION_FAILED");
    var item = query.get(UUID.fromString(actor.getName()), id, modelVersion);
    return ResponseEntity.ok()
        .eTag("\"" + item.version() + "\"")
        .header("Content-Type", "application/json")
        .body(
            com.dulleobang.common.json.StrictJson.stringify(
                FurnitureMapper.response(item, !request.getRequestURI().startsWith("/api/v1/"))));
  }

  @GetMapping({"/api/users/me/furniture", "/api/v1/users/me/furniture"})
  public ResponseEntity<String> list(
      Principal actor,
      HttpServletRequest request,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "20") int limit,
      @RequestParam(required = false) String cursor) {
    boolean mobile = !request.getRequestURI().startsWith("/api/v1/");
    var result =
        mobile && request.getParameter("limit") == null && cursor == null
            ? query.all(UUID.fromString(actor.getName()), status)
            : query.list(
                UUID.fromString(actor.getName()),
                request.getRequestURI(),
                mobile,
                status,
                limit,
                cursor);
    return ResponseEntity.ok()
        .header("Content-Type", "application/json")
        .body(com.dulleobang.common.json.StrictJson.stringify(result));
  }

  @PatchMapping("/api/v1/furniture/{id}")
  public ResponseEntity<String> patch(
      Principal actor,
      @PathVariable UUID id,
      @RequestBody(required = false) byte[] body,
      @RequestHeader(value = "Content-Type", required = false) String type,
      @RequestHeader(value = "If-Match", required = false) String etag) {
    var item =
        command.patch(
            UUID.fromString(actor.getName()), id, etag, input.read(body, type, "FurniturePatch"));
    return ResponseEntity.ok()
        .eTag("\"" + item.version() + "\"")
        .header("Content-Type", "application/json")
        .body(
            com.dulleobang.common.json.StrictJson.stringify(FurnitureMapper.response(item, false)));
  }

  @DeleteMapping("/api/v1/furniture/{id}")
  public ResponseEntity<Void> delete(
      Principal actor,
      @PathVariable UUID id,
      @RequestHeader(value = "If-Match", required = false) String etag) {
    command.delete(UUID.fromString(actor.getName()), id, etag);
    return ResponseEntity.noContent().build();
  }
}
