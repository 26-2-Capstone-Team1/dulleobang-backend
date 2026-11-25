package com.dulleobang.roomstate.controller;

import com.dulleobang.common.contract.*;
import com.dulleobang.common.idempotency.service.IdempotencyService;
import com.dulleobang.common.json.StrictJson;
import com.dulleobang.roomstate.service.*;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
public class RoomStateController {
  private final RoomStateQueryService query;
  private final RoomStateCommandService command;
  private final QueryInput parameters;
  private final JsonInput input;

  public RoomStateController(
      RoomStateQueryService query,
      RoomStateCommandService command,
      QueryInput parameters,
      JsonInput input) {
    this.query = query;
    this.command = command;
    this.parameters = parameters;
    this.input = input;
  }

  @PostMapping("/api/listings/{id}/room-states")
  public ResponseEntity<String> create(
      Principal actor,
      HttpServletRequest request,
      @PathVariable UUID id,
      @RequestBody(required = false) byte[] body,
      @RequestHeader(value = "Content-Type", required = false) String type,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    IdempotencyService.requireKey(key);
    var json = input.readMobile(body, type, "MobilecreateRoomState");
    return HttpResponses.stored(
        command.create(
            UUID.fromString(actor.getName()), id, request.getRequestURI(), key, json, true));
  }

  @GetMapping("/api/listings/{id}/room-states")
  public ResponseEntity<String> list(
      Principal actor,
      HttpServletRequest request,
      @PathVariable UUID id,
      @RequestParam MultiValueMap<String, String> raw) {
    var q = parameters.read("getRoomStates", raw);
    return response(
        query.list(
            UUID.fromString(actor.getName()),
            id,
            request.getRequestURI(),
            q,
            true,
            !raw.containsKey("limit") && !raw.containsKey("cursor")));
  }

  @GetMapping("/api/room-states/{id}")
  public ResponseEntity<String> get(
      Principal actor, @PathVariable UUID id, @RequestParam MultiValueMap<String, String> raw) {
    parameters.read("getRoomState", raw);
    return response(query.get(UUID.fromString(actor.getName()), id, true));
  }

  @DeleteMapping("/api/room-states/{id}")
  public ResponseEntity<Void> delete(Principal actor, @PathVariable UUID id) {
    command.delete(UUID.fromString(actor.getName()), id);
    return ResponseEntity.noContent().build();
  }

  private static ResponseEntity<String> response(Object value) {
    return ResponseEntity.ok()
        .header("Content-Type", "application/json")
        .body(StrictJson.stringify(value));
  }
}
