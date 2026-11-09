package com.dulleobang.space.service;

import com.dulleobang.common.exception.ApiException;
import com.dulleobang.listing.domain.ListingRecord;
import com.dulleobang.listing.service.ListingAccessService;
import com.dulleobang.space.domain.SpaceRecord;
import com.dulleobang.space.mapper.MobileSpaceMapper;
import com.dulleobang.space.repository.SpaceRepository;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class SpaceQueryService {
  private final SpaceRepository repository;
  private final ListingAccessService access;
  private final BundlePresentationService bundles;

  public SpaceQueryService(
      SpaceRepository repository, ListingAccessService access, BundlePresentationService bundles) {
    this.repository = repository;
    this.access = access;
    this.bundles = bundles;
  }

  public SpaceRecord get(UUID actor, UUID listing, Integer version) {
    return get(access.get(actor, listing, false, false, false), version, false);
  }

  public SpaceRecord get(ListingRecord listing, Integer version, boolean lock) {
    Integer selected = version == null ? current(listing) : version;
    if (selected == null) throw new ApiException(409, "SPACE_NOT_READY");
    return repository
        .find(listing.id(), selected, lock)
        .orElseThrow(() -> new ApiException(404, "RESOURCE_NOT_FOUND"));
  }

  public static Integer current(ListingRecord listing) {
    var value = listing.fields().get("currentSpaceVersion");
    return value == null || value.isNull() ? null : value.asInt();
  }

  public Map<String, Object> response(UUID actor, UUID listing, Integer version, boolean mobile) {
    var target = access.get(actor, listing, false, false, false);
    var space = get(target, version, false);
    var value = new LinkedHashMap<String, Object>();
    value.put("listingId", listing);
    value.put("spaceVersion", space.spaceVersion());
    value.put("objectsVersion", space.objectsVersion());
    value.put("isCurrent", Objects.equals(current(target), space.spaceVersion()));
    value.put("bundle", bundles.present(space.bundle()));
    value.put(
        "createdAt",
        new DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(space.createdAt()));
    return mobile ? MobileSpaceMapper.project(value, true) : value;
  }

  public Map<String, Object> objects(SpaceRecord space, boolean mobile) {
    return mobile
        ? Map.of(
            "objectsVersion",
            space.objectsVersion(),
            "objects",
            MobileSpaceMapper.objects(space.bundle().path("objects")))
        : Map.of(
            "listingId",
            space.listingId(),
            "spaceVersion",
            space.spaceVersion(),
            "version",
            space.objectsVersion(),
            "objects",
            space.bundle().path("objects"));
  }
}
