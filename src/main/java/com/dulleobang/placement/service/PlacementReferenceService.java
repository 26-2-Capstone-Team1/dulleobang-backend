package com.dulleobang.placement.service;

import com.dulleobang.common.exception.ApiException;
import com.dulleobang.furniture.domain.FurnitureItem;
import com.dulleobang.furniture.service.FurnitureQueryService;
import com.dulleobang.listing.domain.ListingRecord;
import com.dulleobang.placement.repository.PlacementReferenceRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class PlacementReferenceService {
  private final PlacementReferenceRepository repository;
  private final FurnitureQueryService furniture;

  public PlacementReferenceService(
      PlacementReferenceRepository repository, FurnitureQueryService furniture) {
    this.repository = repository;
    this.furniture = furniture;
  }

  public int current(ListingRecord listing) {
    if (listing.fields().path("currentSpaceVersion").isNull())
      throw new ApiException(409, "SPACE_NOT_READY");
    return listing.fields().path("currentSpaceVersion").asInt();
  }

  public void space(UUID actor, ListingRecord listing, JsonNode selection) {
    int version = selection.path("spaceVersion").asInt();
    repository.requireSpace(listing.id(), version);
    if (current(listing) != version) throw new ApiException(409, "STALE_SPACE_VERSION");
    if (selection.path("kind").asText().equals("ROOM_STATE"))
      repository.requireState(
          actor, UUID.fromString(selection.path("roomStateId").asText()), listing.id(), version);
  }

  public Map<UUID, FurnitureItem> lockFurniture(UUID actor, JsonNode items) {
    var ids = new TreeSet<UUID>();
    for (var item : items) ids.add(UUID.fromString(item.path("furnitureId").asText()));
    var result = new HashMap<UUID, FurnitureItem>();
    for (UUID id : ids) result.put(id, furniture.owned(actor, id, true, false));
    return result;
  }

  public void models(JsonNode items, Map<UUID, FurnitureItem> models) {
    for (var item : items) {
      var model = models.get(UUID.fromString(item.path("furnitureId").asText()));
      if (!model.status().equals("READY") || model.model() == null)
        throw new ApiException(409, "FURNITURE_NOT_READY");
      if (model.model().path("modelVersion").asInt() != item.path("modelVersion").asInt())
        throw new ApiException(409, "STALE_MODEL_VERSION");
    }
  }
}
