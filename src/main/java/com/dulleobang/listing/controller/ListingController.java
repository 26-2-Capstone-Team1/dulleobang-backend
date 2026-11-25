package com.dulleobang.listing.controller;

import com.dulleobang.common.exception.ApiException;
import com.dulleobang.common.idempotency.service.IdempotencyService;
import com.dulleobang.common.json.StrictJson;
import com.dulleobang.listing.dto.ListingCreateRequest;
import com.dulleobang.listing.dto.ListingPage;
import com.dulleobang.listing.dto.ListingResponse;
import com.dulleobang.listing.service.ListingCommandService;
import com.dulleobang.listing.service.ListingQueryService;
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

  public ListingController(ListingCommandService command, ListingQueryService query) {
    this.command = command;
    this.query = query;
  }

  @PostMapping("/api/listings")
  public ResponseEntity<ListingResponse> create(
      Principal actor,
      @Valid @RequestBody(required = false) ListingCreateRequest body,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    IdempotencyService.requireKey(key);
    if (body == null) throw new ApiException(422, "BODY_REQUIRED");
    var response = command.createMobile(userId(actor), key, body);
    var builder = ResponseEntity.status(response.status()).contentType(MediaType.APPLICATION_JSON);
    response.headers().forEach(builder::header);
    return builder.body(StrictJson.bind(StrictJson.parse(response.body()), ListingResponse.class));
  }

  @GetMapping("/api/listings/{id}")
  public ResponseEntity<ListingResponse> get(Principal actor, @PathVariable UUID id) {
    var listing = query.getMobile(userId(actor), id);
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_JSON)
        .eTag("\"" + listing.version() + "\"")
        .body(listing.body());
  }

  @GetMapping("/api/listings")
  public ResponseEntity<ListingPage<ListingResponse>> search(
      Principal actor, @RequestParam MultiValueMap<String, String> raw) {
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_JSON)
        .body(query.listMobile(userId(actor), raw, false));
  }

  @GetMapping("/api/users/me/listings")
  public ResponseEntity<ListingPage<ListingResponse>> mine(
      Principal actor, @RequestParam MultiValueMap<String, String> raw) {
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_JSON)
        .body(query.listMobile(userId(actor), raw, true));
  }

  private UUID userId(Principal actor) {
    return UUID.fromString(actor.getName());
  }
}
