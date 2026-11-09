package com.dulleobang.placement.service;

import com.dulleobang.common.exception.ApiException;
import com.dulleobang.common.pagination.service.CollectionPageService;
import com.dulleobang.furniture.service.FurnitureQueryService;
import com.dulleobang.listing.service.ListingAccessService;
import com.dulleobang.placement.domain.PlacementRecord;
import com.dulleobang.placement.repository.PlacementRepository;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlacementQueryService {
  private final PlacementRepository repository;
  private final FurnitureQueryService furniture;
  private final ListingAccessService listings;
  private final CollectionPageService pages;

  public PlacementQueryService(
      PlacementRepository repository,
      FurnitureQueryService furniture,
      ListingAccessService listings,
      CollectionPageService pages) {
    this.repository = repository;
    this.furniture = furniture;
    this.listings = listings;
    this.pages = pages;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public PlacementRecord get(UUID actor, UUID listing) {
    furniture.requireTenant(actor);
    listings.get(actor, listing, false, false, false);
    return repository
        .find(actor, listing, false)
        .orElseThrow(() -> new ApiException(404, "PLACEMENT_NOT_FOUND"));
  }

  @Transactional(isolation = Isolation.REPEATABLE_READ)
  public Map<String, Object> list(UUID actor, String path, Map<String, Object> query) {
    furniture.requireTenant(actor);
    return pages.page(
        actor,
        path,
        query,
        repository.visible(actor),
        p -> p.id().toString(),
        PlacementRecord::value,
        false);
  }
}
