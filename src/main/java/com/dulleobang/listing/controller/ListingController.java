package com.dulleobang.listing.controller;

import com.dulleobang.common.contract.*;
import com.dulleobang.common.contract.HttpResponses;
import com.dulleobang.common.idempotency.service.IdempotencyService;
import com.dulleobang.common.json.StrictJson;
import com.dulleobang.listing.mapper.*;
import com.dulleobang.listing.service.*;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
public class ListingController {
  private final ListingAccessService access;
  private final ListingCommandService command;
  private final ListingQueryService query;
  private final ListingViewService views;
  private final MobileListingInputMapper mobileInput;
  private final JsonInput input;
  private final ContractValidator contracts;
  private final QueryInput parameters;
  private final MobileSearchMapper search;

  public ListingController(
      ListingAccessService access,
      ListingCommandService command,
      ListingQueryService query,
      ListingViewService views,
      MobileListingInputMapper mobileInput,
      JsonInput input,
      ContractValidator contracts,
      QueryInput parameters,
      MobileSearchMapper search) {
    this.access = access;
    this.command = command;
    this.query = query;
    this.views = views;
    this.mobileInput = mobileInput;
    this.input = input;
    this.contracts = contracts;
    this.parameters = parameters;
    this.search = search;
  }

  @PostMapping({"/api/listings", "/api/v1/listings"})
  public ResponseEntity<String> create(
      Principal actor,
      HttpServletRequest request,
      @RequestBody(required = false) byte[] body,
      @RequestHeader(value = "Content-Type", required = false) String type,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    IdempotencyService.requireKey(key);
    boolean mobile = mobile(request);
    JsonNode json =
        mobile
            ? mobileInput.convert(input.readMobile(body, type, "MobilecreateListing"))
            : input.read(body, type, "ListingCreate");
    contracts.validate("ListingCreate", StrictJson.stringify(json));
    return HttpResponses.stored(
        command.create(
            UUID.fromString(actor.getName()), request.getRequestURI(), key, json, mobile));
  }

  @GetMapping({"/api/listings/{id}", "/api/v1/listings/{id}"})
  public ResponseEntity<String> get(
      Principal actor, HttpServletRequest request, @PathVariable UUID id) {
    UUID user = UUID.fromString(actor.getName());
    var item = access.get(user, id, false, false, false);
    return HttpResponses.json(views.response(user, item, false, mobile(request)), item.version());
  }

  @GetMapping({
    "/api/listings",
    "/api/v1/listings",
    "/api/users/me/listings",
    "/api/v1/users/me/listings"
  })
  public ResponseEntity<String> list(
      Principal actor,
      HttpServletRequest request,
      @RequestParam MultiValueMap<String, String> raw) {
    boolean mine = request.getRequestURI().contains("/users/me/"),
        mobile = mobile(request),
        aggregate = mobile && !raw.containsKey("limit") && !raw.containsKey("cursor");
    var values = mobile && !mine ? search.convert(raw) : raw;
    var q = parameters.read(mine ? "getMyListings" : "searchListings", values);
    return HttpResponses.json(
        query.list(
            UUID.fromString(actor.getName()), request.getRequestURI(), q, mine, mobile, aggregate),
        null);
  }

  @PatchMapping("/api/v1/listings/{id}")
  public ResponseEntity<String> patch(
      Principal actor,
      @PathVariable UUID id,
      @RequestBody(required = false) byte[] body,
      @RequestHeader(value = "Content-Type", required = false) String type,
      @RequestHeader(value = "If-Match", required = false) String etag) {
    var value =
        command.patch(
            UUID.fromString(actor.getName()), id, etag, input.read(body, type, "ListingPatch"));
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
            UUID.fromString(actor.getName()),
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
    command.delete(UUID.fromString(actor.getName()), id, etag);
    return ResponseEntity.noContent().build();
  }

  private boolean mobile(HttpServletRequest request) {
    return !request.getRequestURI().startsWith("/api/v1/");
  }
}
