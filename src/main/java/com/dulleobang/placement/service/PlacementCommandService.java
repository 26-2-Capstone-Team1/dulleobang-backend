package com.dulleobang.placement.service;

import com.dulleobang.common.contract.ContractValidator;
import com.dulleobang.common.json.StrictJson;
import com.dulleobang.common.version.ETagParser;
import com.dulleobang.furniture.service.FurnitureQueryService;
import com.dulleobang.listing.service.ListingAccessService;
import com.dulleobang.placement.domain.PlacementRecord;
import com.dulleobang.placement.mapper.MobilePlacementMapper;
import com.dulleobang.placement.repository.PlacementRepository;
import com.dulleobang.placement.validation.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlacementCommandService {
  private final PlacementRepository repository;
  private final PlacementReferenceService refs;
  private final FurnitureQueryService furniture;
  private final ListingAccessService listings;
  private final PlacementInputPolicy policy;
  private final MobilePlacementMapper mobile;
  private final ContractValidator contracts;

  public PlacementCommandService(
      PlacementRepository repository,
      PlacementReferenceService refs,
      FurnitureQueryService furniture,
      ListingAccessService listings,
      PlacementInputPolicy policy,
      MobilePlacementMapper mobile,
      ContractValidator contracts) {
    this.repository = repository;
    this.refs = refs;
    this.furniture = furniture;
    this.listings = listings;
    this.policy = policy;
    this.mobile = mobile;
    this.contracts = contracts;
  }

  @Transactional
  public PlacementRecord save(
      UUID actor, UUID listing, JsonNode input, String match, String none, boolean mobileInput) {
    furniture.requireTenant(actor);
    var parent = listings.get(actor, listing, false, true, false);
    var models = refs.lockFurniture(actor, input.path("placements"));
    var existing = repository.find(actor, listing, true).orElse(null);
    PlacementPreconditions.check(existing, match, none);
    var value =
        mobileInput ? mobile.canonical(input, refs.current(parent), models) : input.deepCopy();
    contracts.validate("PlacementPut", StrictJson.stringify(value));
    refs.space(actor, parent, value.path("space"));
    refs.models(value.path("placements"), models);
    Instant now = Instant.now();
    policy.validate(value, now);
    repository.save(
        existing == null ? UUID.randomUUID() : existing.id(),
        actor,
        listing,
        existing == null,
        value,
        now);
    return repository.find(actor, listing, false).orElseThrow();
  }

  @Transactional
  public void delete(UUID actor, UUID listing, String match) {
    furniture.requireTenant(actor);
    listings.get(actor, listing, false, true, false);
    var existing = repository.find(actor, listing, true);
    if (existing.isPresent()) {
      ETagParser.requireMatch(match, existing.get().version());
      repository.delete(existing.get().id());
    }
  }
}
