package com.dulleobang.listing.service;

import com.dulleobang.common.contract.ContractValidator;
import com.dulleobang.common.exception.ApiException;
import com.dulleobang.common.idempotency.*;
import com.dulleobang.common.idempotency.service.IdempotencyService;
import com.dulleobang.common.json.StrictJson;
import com.dulleobang.common.version.ETagParser;
import com.dulleobang.listing.dto.ListingCreateRequest;
import com.dulleobang.listing.dto.ListingResponse;
import com.dulleobang.listing.mapper.MobileListingInputMapper;
import com.dulleobang.listing.repository.ListingRepository;
import com.dulleobang.listing.validation.ListingFieldsPolicy;
import com.dulleobang.upload.repository.UploadRepository;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ListingCommandService {
  private final ListingRepository repository;
  private final ListingAccessService access;
  private final ListingFieldsPolicy fields;
  private final ListingViewService views;
  private final UploadRepository uploads;
  private final IdempotencyService idempotency;
  private final Clock clock;
  private final MobileListingInputMapper mobileInput;
  private final ContractValidator contracts;
  private final com.dulleobang.job.service.JobCommandService jobs;

  public ListingCommandService(
      ListingRepository repository,
      ListingAccessService access,
      ListingFieldsPolicy fields,
      ListingViewService views,
      UploadRepository uploads,
      IdempotencyService idempotency,
      Clock clock,
      MobileListingInputMapper mobileInput,
      ContractValidator contracts,
      com.dulleobang.job.service.JobCommandService jobs) {
    this.repository = repository;
    this.access = access;
    this.fields = fields;
    this.views = views;
    this.uploads = uploads;
    this.idempotency = idempotency;
    this.clock = clock;
    this.mobileInput = mobileInput;
    this.contracts = contracts;
    this.jobs = jobs;
  }

  public StoredResponse create(UUID actor, String key, ListingCreateRequest request) {
    var input = mobileInput.convert(request);
    contracts.validate("ListingCreate", StrictJson.stringify(input));
    var validated = fields.merge(input, null);
    return idempotency.execute(
        actor.toString(),
        "POST",
        "/api/listings",
        key,
        StrictJson.stringify(input),
        () -> {
          UUID id = UUID.randomUUID();
          repository.create(id, actor, validated, clock.instant());
          var item = access.get(actor, id, true, false, false);
          return new StoredResponse(
              201,
              StrictJson.stringify(views.mobile(actor, item)),
              Map.of("Location", "/api/listings/" + id, "ETag", "\"1\""));
        });
  }

  @Transactional
  public ListingResponse.Versioned patch(UUID actor, UUID id, String etag, ObjectNode request) {
    var item = access.get(actor, id, true, true, false);
    ETagParser.requireMatch(etag, item.version());
    if (item.status().equals("ARCHIVED")) throw new ApiException(409, "INVALID_STATE");
    var input = mobileInput.patch(request, item.fields());
    contracts.validate("ListingPatch", StrictJson.stringify(input));
    var update = fields.merge(input, item.fields());
    if (input.has("photoFileIds")) {
      if (item.status().equals("PUBLISHED") && input.path("photoFileIds").isEmpty())
        throw new ApiException(409, "PUBLICATION_REQUIREMENTS_NOT_MET");
      List<UUID> sources = new ArrayList<>(), derived = new ArrayList<>();
      Set<UUID> seen = new HashSet<>();
      for (var value : input.path("photoFileIds")) {
        UUID fileId = UUID.fromString(value.asText());
        if (!seen.add(fileId)) throw new ApiException(422, "DUPLICATE_ID");
        var file =
            uploads
                .asset(fileId, false)
                .orElseThrow(() -> new ApiException(404, "RESOURCE_NOT_FOUND"));
        if (!actor.equals(file.owner())) throw new ApiException(404, "RESOURCE_NOT_FOUND");
        if (!file.targetId().equals(id)
            || !file.purpose().equals("LISTING_PHOTO")
            || !file.verified()
            || file.derivative() == null) throw new ApiException(422, "INVALID_PHOTO");
        sources.add(fileId);
        derived.add(file.derivative());
      }
      repository.replacePhotos(id, sources, derived);
    }
    repository.write(id, update, clock.instant(), true);
    return current(actor, id);
  }

  @Transactional
  public ListingResponse.Versioned publication(UUID actor, UUID id, String etag, String status) {
    var item = access.get(actor, id, true, true, false);
    ETagParser.requireMatch(etag, item.version());
    if (item.status().equals("ARCHIVED") && status.equals("PUBLISHED"))
      throw new ApiException(409, "INVALID_STATE");
    if (status.equals("PUBLISHED")
        && (item.fields().path("currentSpaceVersion").isNull()
            || repository.photoIds(id).isEmpty()))
      throw new ApiException(409, "PUBLICATION_REQUIREMENTS_NOT_MET");
    if (status.equals("ARCHIVED") && !item.fields().path("activeJobId").isNull())
      throw new ApiException(409, "ACTIVE_JOB_EXISTS");
    if (!item.status().equals(status)) repository.publication(id, status, clock.instant());
    return current(actor, id);
  }

  private ListingResponse.Versioned current(UUID actor, UUID id) {
    var item = access.get(actor, id, true, false, false);
    return new ListingResponse.Versioned(views.mobile(actor, item), item.version());
  }

  @Transactional
  public void delete(UUID actor, UUID id, String etag) {
    var item = access.get(actor, id, true, true, true);
    if (item.deleted()) return;
    ETagParser.requireMatch(etag, item.version());
    jobs.cancelTarget("LISTING", id);
    repository.delete(id, clock.instant());
  }
}
