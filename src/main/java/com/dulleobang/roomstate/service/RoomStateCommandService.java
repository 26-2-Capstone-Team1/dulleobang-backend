package com.dulleobang.roomstate.service;

import com.dulleobang.common.exception.ApiException;
import com.dulleobang.common.idempotency.*;
import com.dulleobang.common.idempotency.service.IdempotencyService;
import com.dulleobang.common.json.StrictJson;
import com.dulleobang.job.service.JobCommandService;
import com.dulleobang.listing.domain.ListingRecord;
import com.dulleobang.listing.service.ListingAccessService;
import com.dulleobang.roomstate.repository.RoomStateRepository;
import com.dulleobang.space.domain.SpaceRecord;
import com.dulleobang.space.service.SpaceQueryService;
import com.dulleobang.user.service.UserService;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoomStateCommandService {
  private final ListingAccessService listings;
  private final RoomStateAccessService access;
  private final RoomStateRepository repository;
  private final SpaceQueryService spaces;
  private final JobCommandService jobs;
  private final IdempotencyService idempotency;
  private final UserService users;
  private final Clock clock;

  public RoomStateCommandService(
      ListingAccessService listings,
      RoomStateAccessService access,
      RoomStateRepository repository,
      SpaceQueryService spaces,
      JobCommandService jobs,
      IdempotencyService idempotency,
      UserService users,
      Clock clock) {
    this.listings = listings;
    this.access = access;
    this.repository = repository;
    this.spaces = spaces;
    this.jobs = jobs;
    this.idempotency = idempotency;
    this.users = users;
    this.clock = clock;
  }

  public StoredResponse create(
      UUID actor, UUID listing, String path, String key, JsonNode body, boolean mobile) {
    return idempotency.execute(
        actor.toString(),
        "POST",
        path,
        key,
        StrictJson.stringify(body),
        () -> authorize(actor, listing, true),
        () -> accept(actor, listing, body, mobile));
  }

  private ListingRecord authorize(UUID actor, UUID listing, boolean lock) {
    if (lock) jobs.lockOwner(actor);
    var target = listings.get(actor, listing, false, lock, false);
    if (!target.owner().equals(actor) && !users.get(actor).roles().contains("TENANT"))
      throw new ApiException(403, "FORBIDDEN");
    return target;
  }

  private StoredResponse accept(UUID actor, UUID listing, JsonNode body, boolean mobile) {
    var target = authorize(actor, listing, true);
    if (target.status().equals("ARCHIVED")) throw new ApiException(409, "INVALID_STATE");
    Integer version =
        body.has("sourceSpaceVersion") ? body.path("sourceSpaceVersion").asInt() : null;
    var source = spaces.get(target, version, true);
    int objectVersion =
        body.has("sourceObjectsVersion")
            ? body.path("sourceObjectsVersion").asInt()
            : source.objectsVersion();
    if (!Objects.equals(SpaceQueryService.current(target), source.spaceVersion())
        || source.objectsVersion() != objectVersion)
      throw new ApiException(409, "STALE_SPACE_VERSION");
    var removed = removals(body, source);
    UUID id = UUID.randomUUID();
    repository.create(id, actor, source, removed, clock.instant());
    var job = jobs.create(actor, "ROOM_OBJECT_REMOVAL", "ROOM_STATE", id, listing, null, null);
    return new StoredResponse(
        202,
        StrictJson.stringify(
            Map.of(
                "roomStateId", id, "jobId", job.id(), "status", "QUEUED", "pollAfterSeconds", 3)),
        Map.of("Location", "/api/v1/ai-jobs/" + job.id(), "Retry-After", "3"));
  }

  private List<UUID> removals(JsonNode body, SpaceRecord source) {
    var objects = new HashMap<String, JsonNode>();
    for (var object : source.bundle().path("objects"))
      objects.put(object.path("objectId").asText(), object);
    var removed = new LinkedHashSet<UUID>();
    for (var id : body.path("removedObjectIds")) {
      UUID uuid = UUID.fromString(id.asText());
      if (!removed.add(uuid)) throw new ApiException(422, "DUPLICATE_ID");
      var object = objects.get(uuid.toString());
      if (object == null) throw new ApiException(422, "UNKNOWN_OBJECT");
      if (!object.path("ownershipType").asText().equals("PERSONAL")
          || !object.path("removable").asBoolean())
        throw new ApiException(422, "OBJECT_NOT_REMOVABLE");
    }
    return List.copyOf(removed);
  }

  @Transactional
  public void delete(UUID actor, UUID id) {
    var state = access.get(actor, id, true, true);
    if (state.deleted()) return;
    if (repository.referenced(id)) throw new ApiException(409, "RESOURCE_IN_USE");
    jobs.cancelTarget("ROOM_STATE", id);
    repository.delete(id, clock.instant());
  }
}
