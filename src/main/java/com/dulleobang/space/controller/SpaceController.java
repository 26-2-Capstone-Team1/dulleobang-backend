package com.dulleobang.space.controller;

import com.dulleobang.common.contract.*;
import com.dulleobang.common.json.StrictJson;
import com.dulleobang.space.service.*;
import java.security.Principal;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
public class SpaceController {
  private final SpaceQueryService query;
  private final SpaceCommandService command;
  private final QueryInput parameters;
  private final JsonInput input;

  public SpaceController(
      SpaceQueryService query,
      SpaceCommandService command,
      QueryInput parameters,
      JsonInput input) {
    this.query = query;
    this.command = command;
    this.parameters = parameters;
    this.input = input;
  }

  @GetMapping("/api/listings/{id}/space")
  public ResponseEntity<String> space(
      Principal actor, @PathVariable UUID id, @RequestParam MultiValueMap<String, String> raw) {
    var q = parameters.read("getSpace", raw);
    return response(query.response(UUID.fromString(actor.getName()), id, version(q), true), null);
  }

  @GetMapping("/api/listings/{id}/objects")
  public ResponseEntity<String> objects(
      Principal actor, @PathVariable UUID id, @RequestParam MultiValueMap<String, String> raw) {
    var q = parameters.read("getObjects", raw);
    var space = query.get(UUID.fromString(actor.getName()), id, version(q));
    return response(query.objects(space, true), space.objectsVersion());
  }

  @PatchMapping("/api/listings/{id}/objects")
  public ResponseEntity<String> classify(
      Principal actor,
      @PathVariable UUID id,
      @RequestBody(required = false) byte[] body,
      @RequestHeader(value = "Content-Type", required = false) String type,
      @RequestHeader(value = "If-Match", required = false) String etag) {
    var json = input.readMobile(body, type, "MobileclassifyObjects");
    var space = command.classify(UUID.fromString(actor.getName()), id, etag, json, true);
    return response(query.objects(space, true), space.objectsVersion());
  }

  private static Integer version(Map<String, Object> q) {
    return q.containsKey("spaceVersion") ? ((Number) q.get("spaceVersion")).intValue() : null;
  }

  private static ResponseEntity<String> response(Object value, Integer version) {
    var builder = ResponseEntity.ok().header("Content-Type", "application/json");
    if (version != null) builder.eTag(Character.toString(34) + version + Character.toString(34));
    return builder.body(StrictJson.stringify(value));
  }
}
