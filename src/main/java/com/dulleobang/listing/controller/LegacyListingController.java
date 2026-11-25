package com.dulleobang.listing.controller;

import com.dulleobang.common.contract.HttpResponses;
import com.dulleobang.common.contract.JsonInput;
import com.dulleobang.common.idempotency.service.IdempotencyService;
import com.dulleobang.listing.service.ListingCommandService;
import com.dulleobang.listing.service.ListingQueryService;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
public class LegacyListingController {
  private final ListingCommandService command;
  private final ListingQueryService query;
  private final JsonInput input;

  public LegacyListingController(
      ListingCommandService command, ListingQueryService query, JsonInput input) {
    this.command = command;
    this.query = query;
    this.input = input;
  }

  @PostMapping("/api/v1/listings")
  public ResponseEntity<String> create(
      Principal actor,
      @RequestBody(required = false) byte[] body,
      @RequestHeader(value = "Content-Type", required = false) String type,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    IdempotencyService.requireKey(key);
    return HttpResponses.stored(
        command.createLegacy(userId(actor), key, input.read(body, type, "ListingCreate")));
  }

  @GetMapping("/api/v1/listings/{id}")
  public ResponseEntity<String> get(Principal actor, @PathVariable UUID id) {
    var listing = query.getLegacy(userId(actor), id);
    return HttpResponses.json(listing.body(), listing.version());
  }

  @GetMapping("/api/v1/listings")
  public ResponseEntity<String> search(
      Principal actor, @RequestParam MultiValueMap<String, String> raw) {
    return HttpResponses.json(query.listLegacy(userId(actor), raw, false));
  }

  @GetMapping("/api/v1/users/me/listings")
  public ResponseEntity<String> mine(
      Principal actor, @RequestParam MultiValueMap<String, String> raw) {
    return HttpResponses.json(query.listLegacy(userId(actor), raw, true));
  }

  @PatchMapping("/api/v1/listings/{id}")
  public ResponseEntity<String> patch(
      Principal actor,
      @PathVariable UUID id,
      @RequestBody(required = false) byte[] body,
      @RequestHeader(value = "Content-Type", required = false) String type,
      @RequestHeader(value = "If-Match", required = false) String etag) {
    var value = command.patch(userId(actor), id, etag, input.read(body, type, "ListingPatch"));
    return HttpResponses.json(value, value.path("version").asLong());
  }

  @PutMapping("/api/v1/listings/{id}/publication")
  public ResponseEntity<String> publication(
      Principal actor,
      @PathVariable UUID id,
      @RequestBody(required = false) byte[] body,
      @RequestHeader(value = "Content-Type", required = false) String type,
      @RequestHeader(value = "If-Match", required = false) String etag) {
    var value =
        command.publication(
            userId(actor),
            id,
            etag,
            input.read(body, type, "PublicationRequest").path("status").asText());
    return HttpResponses.json(value, value.path("version").asLong());
  }

  @DeleteMapping("/api/v1/listings/{id}")
  public ResponseEntity<Void> delete(
      Principal actor,
      @PathVariable UUID id,
      @RequestHeader(value = "If-Match", required = false) String etag) {
    command.delete(userId(actor), id, etag);
    return ResponseEntity.noContent().build();
  }

  private UUID userId(Principal actor) {
    return UUID.fromString(actor.getName());
  }
}
