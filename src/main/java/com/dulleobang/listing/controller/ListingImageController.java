package com.dulleobang.listing.controller;

import com.dulleobang.common.contract.HttpResponses;
import com.dulleobang.common.idempotency.service.IdempotencyService;
import com.dulleobang.listing.service.*;
import com.dulleobang.upload.controller.MultipartInput;
import java.security.Principal;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartHttpServletRequest;

@RestController
public class ListingImageController {
  private final ListingPhotoService photos;
  private final ListingAccessService access;
  private final MultipartInput input;

  public ListingImageController(
      ListingPhotoService photos, ListingAccessService access, MultipartInput input) {
    this.photos = photos;
    this.access = access;
    this.input = input;
  }

  @PostMapping(value = "/api/listings/{id}/images", consumes = "multipart/form-data")
  public ResponseEntity<String> upload(
      Principal actor,
      @PathVariable UUID id,
      MultipartHttpServletRequest request,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    IdempotencyService.requireKey(key);
    UUID user = UUID.fromString(actor.getName());
    access.get(user, id, true, false, false);
    input.fields(request, Set.of());
    return HttpResponses.stored(
        photos.upload(user, id, request.getRequestURI(), key, input.files(request, "images", 30)));
  }
}
