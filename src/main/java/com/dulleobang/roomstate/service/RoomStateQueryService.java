package com.dulleobang.roomstate.service;

import com.dulleobang.common.pagination.service.CollectionPageService;
import com.dulleobang.listing.service.ListingAccessService;
import com.dulleobang.roomstate.domain.RoomStateRecord;
import com.dulleobang.roomstate.repository.RoomStateRepository;
import com.dulleobang.space.mapper.MobileSpaceMapper;
import com.dulleobang.space.service.BundlePresentationService;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class RoomStateQueryService {
  private final RoomStateAccessService access;
  private final RoomStateRepository repository;
  private final ListingAccessService listings;
  private final CollectionPageService pages;
  private final BundlePresentationService bundles;

  public RoomStateQueryService(
      RoomStateAccessService access,
      RoomStateRepository repository,
      ListingAccessService listings,
      CollectionPageService pages,
      BundlePresentationService bundles) {
    this.access = access;
    this.repository = repository;
    this.listings = listings;
    this.pages = pages;
    this.bundles = bundles;
  }

  public Map<String, Object> get(UUID actor, UUID id, boolean mobile) {
    return response(access.get(actor, id, false, false), mobile);
  }

  public Map<String, Object> list(
      UUID actor,
      UUID listing,
      String path,
      Map<String, Object> query,
      boolean mobile,
      boolean aggregate) {
    listings.get(actor, listing, false, false, false);
    return pages.page(
        actor,
        path,
        query,
        repository.list(actor, listing),
        x -> x.id().toString(),
        x -> response(x, mobile),
        aggregate);
  }

  public Map<String, Object> response(RoomStateRecord state, boolean mobile) {
    var value = new LinkedHashMap<String, Object>();
    value.put("roomStateId", state.id());
    value.put("listingId", state.listingId());
    value.put("createdBy", state.owner());
    value.put("sourceSpaceVersion", state.sourceSpaceVersion());
    value.put("sourceObjectsVersion", state.sourceObjectsVersion());
    value.put("removedObjectIds", state.removedObjectIds());
    value.put("status", state.status());
    value.put("jobId", state.jobId());
    value.put("bundle", state.status().equals("READY") ? bundles.present(state.bundle()) : null);
    value.put(
        "createdAt",
        new DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(state.createdAt()));
    return mobile ? MobileSpaceMapper.project(value, false) : value;
  }
}
