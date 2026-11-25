package com.dulleobang.placement.controller;

import com.dulleobang.common.contract.*;
import com.dulleobang.common.json.StrictJson;
import com.dulleobang.placement.domain.PlacementRecord;
import com.dulleobang.placement.mapper.MobilePlacementMapper;
import com.dulleobang.placement.service.*;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
public class PlacementController {
  private final PlacementCommandService command;
  private final PlacementQueryService query;
  private final MobilePlacementMapper mobile;
  private final JsonInput input;
  private final QueryInput parameters;

  public PlacementController(
      PlacementCommandService command,
      PlacementQueryService query,
      MobilePlacementMapper mobile,
      JsonInput input,
      QueryInput parameters) {
    this.command = command;
    this.query = query;
    this.mobile = mobile;
    this.input = input;
    this.parameters = parameters;
  }

  @GetMapping("/api/listings/{id}/placements")
  public ResponseEntity<String> get(
      Principal actor, @PathVariable UUID id, @RequestParam MultiValueMap<String, String> raw) {
    parameters.read("getPlacements", raw);
    return response(query.get(UUID.fromString(actor.getName()), id));
  }

  @PutMapping("/api/listings/{id}/placements")
  public ResponseEntity<String> save(
      Principal actor,
      @PathVariable UUID id,
      @RequestBody(required = false) byte[] body,
      @RequestHeader(value = "Content-Type", required = false) String type,
      @RequestHeader(value = "If-Match", required = false) String match,
      @RequestHeader(value = "If-None-Match", required = false) String none,
      @RequestParam MultiValueMap<String, String> raw) {
    parameters.read("savePlacements", raw);
    var value = input.readMobile(body, type, "MobilesavePlacements");
    return response(command.save(UUID.fromString(actor.getName()), id, value, match, none, true));
  }

  @DeleteMapping("/api/listings/{id}/placements")
  public ResponseEntity<Void> delete(
      Principal actor,
      @PathVariable UUID id,
      @RequestHeader(value = "If-Match", required = false) String match,
      @RequestParam MultiValueMap<String, String> raw) {
    parameters.read("deletePlacements", raw);
    command.delete(UUID.fromString(actor.getName()), id, match);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/api/users/me/placements")
  public ResponseEntity<String> list(
      Principal actor,
      HttpServletRequest request,
      @RequestParam MultiValueMap<String, String> raw) {
    var result =
        query.list(
            UUID.fromString(actor.getName()),
            request.getRequestURI(),
            parameters.read("getMyPlacements", raw));
    return ResponseEntity.ok()
        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
        .body(StrictJson.stringify(result));
  }

  private ResponseEntity<String> response(PlacementRecord record) {
    return ResponseEntity.ok()
        .eTag("\"" + record.version() + "\"")
        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
        .body(StrictJson.stringify(mobile.response(record.value())));
  }
}
