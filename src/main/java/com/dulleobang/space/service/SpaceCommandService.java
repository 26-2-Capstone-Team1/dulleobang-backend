package com.dulleobang.space.service;

import com.dulleobang.common.exception.ApiException;
import com.dulleobang.common.version.ETagParser;
import com.dulleobang.listing.service.ListingAccessService;
import com.dulleobang.space.domain.SpaceRecord;
import com.dulleobang.space.repository.SpaceRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SpaceCommandService {
  private final ListingAccessService access;
  private final SpaceQueryService query;
  private final SpaceRepository repository;

  public SpaceCommandService(
      ListingAccessService access, SpaceQueryService query, SpaceRepository repository) {
    this.access = access;
    this.query = query;
    this.repository = repository;
  }

  @Transactional
  public SpaceRecord classify(
      UUID actor, UUID listing, String etag, JsonNode body, boolean mobile) {
    var target = access.get(actor, listing, true, true, false);
    var space = query.get(target, mobile ? null : body.path("spaceVersion").asInt(), true);
    if (!Objects.equals(SpaceQueryService.current(target), space.spaceVersion()))
      throw new ApiException(409, "STALE_SPACE_VERSION");
    if (target.status().equals("ARCHIVED")) throw new ApiException(409, "INVALID_STATE");
    ETagParser.requireMatch(etag, space.objectsVersion());
    var objects = new HashMap<String, ObjectNode>();
    for (var object : space.bundle().path("objects"))
      objects.put(object.path("objectId").asText(), (ObjectNode) object);
    var seen = new HashSet<String>();
    for (var change : body.path("objects")) {
      String id = change.path("objectId").asText();
      if (!seen.add(id)) throw new ApiException(422, "DUPLICATE_ID");
      if (!objects.containsKey(id)) throw new ApiException(422, "UNKNOWN_OBJECT");
    }
    for (var change : body.path("objects"))
      objects
          .get(change.path("objectId").asText())
          .put("ownershipType", change.path("ownershipType").asText());
    return repository.classify(space, space.bundle());
  }
}
