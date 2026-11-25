package com.dulleobang.listing.controller;

import com.dulleobang.common.contract.HttpResponses;
import com.dulleobang.common.contract.JsonInput;
import com.dulleobang.common.exception.ApiException;
import com.dulleobang.common.idempotency.service.IdempotencyService;
import com.dulleobang.common.json.StrictJson;
import com.dulleobang.listing.dto.ListingCreateRequest;
import com.dulleobang.listing.dto.ListingPage;
import com.dulleobang.listing.dto.ListingResponse;
import com.dulleobang.listing.service.ListingCommandService;
import com.dulleobang.listing.service.ListingQueryService;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
public class ListingController {
  private final ListingCommandService command;
  private final ListingQueryService query;

  private final JsonInput input;

  public ListingController(
      ListingCommandService command, ListingQueryService query, JsonInput input) {
    this.command = command;
    this.query = query;
    this.input = input;
  }

  @PostMapping("/api/listings")
  public ResponseEntity<ListingResponse> create(
      Principal actor,
      @Valid @RequestBody(required = false) ListingCreateRequest body,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    IdempotencyService.requireKey(key);
    if (body == null) throw new ApiException(422, "BODY_REQUIRED");
    var response = command.create(userId(actor), key, body);
    var builder = ResponseEntity.status(response.status()).contentType(MediaType.APPLICATION_JSON);
    HttpResponses.applyStoredHeaders(builder, response);
    return builder.body(StrictJson.bind(StrictJson.parse(response.body()), ListingResponse.class));
  }

  @GetMapping("/api/listings/{id}")
  public ResponseEntity<ListingResponse> get(Principal actor, @PathVariable UUID id) {
    return response(query.get(userId(actor), id));
  }

  @GetMapping("/api/listings")
  public ResponseEntity<ListingPage<ListingResponse>> search(
      Principal actor, @RequestParam MultiValueMap<String, String> raw) {
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_JSON)
        .body(query.list(userId(actor), raw, false));
  }

  @GetMapping("/api/users/me/listings")
  public ResponseEntity<ListingPage<ListingResponse>> mine(
      Principal actor, @RequestParam MultiValueMap<String, String> raw) {
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_JSON)
        .body(query.list(userId(actor), raw, true));
  }

  @PatchMapping("/api/listings/{id}")
  public ResponseEntity<ListingResponse> patch(
      Principal actor,
      @PathVariable UUID id,
      @RequestBody(required = false) byte[] body,
      @RequestHeader(value = "Content-Type", required = false) String type,
      @RequestHeader(value = "If-Match", required = false) String etag) {
    var patch = (ObjectNode) input.readMobile(body, type, "AppListingPatch");
    return response(command.patch(userId(actor), id, etag, patch));
  }

  @PutMapping("/api/listings/{id}/publication")
  public ResponseEntity<ListingResponse> publication(
      Principal actor,
      @PathVariable UUID id,
      @RequestBody(required = false) byte[] body,
      @RequestHeader(value = "Content-Type", required = false) String type,
      @RequestHeader(value = "If-Match", required = false) String etag) {
    var request = input.read(body, type, "PublicationRequest");
    return response(command.publication(userId(actor), id, etag, request.path("status").asText()));
  }

  @DeleteMapping("/api/listings/{id}")
  public ResponseEntity<Void> delete(
      Principal actor,
      @PathVariable UUID id,
      @RequestHeader(value = "If-Match", required = false) String etag) {
    command.delete(userId(actor), id, etag);
    return ResponseEntity.noContent().build();
  }

  private ResponseEntity<ListingResponse> response(ListingResponse.Versioned listing) {
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_JSON)
        .eTag("\"" + listing.version() + "\"")
        .body(listing.body());
  }

  private UUID userId(Principal actor) {
    return UUID.fromString(actor.getName());
  }
}
