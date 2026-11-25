package com.dulleobang.furniture.service;

import com.dulleobang.common.idempotency.*;
import com.dulleobang.common.idempotency.service.IdempotencyService;
import com.dulleobang.common.json.StrictJson;
import com.dulleobang.common.version.ETagParser;
import com.dulleobang.furniture.domain.FurnitureItem;
import com.dulleobang.furniture.mapper.*;
import com.dulleobang.furniture.repository.FurnitureRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FurnitureCommandService {
  private final FurnitureRepository repository;
  private final FurnitureQueryService query;
  private final IdempotencyService idempotency;
  private final Clock clock;
  private final com.dulleobang.job.service.JobCommandService jobs;

  public FurnitureCommandService(
      FurnitureRepository repository,
      FurnitureQueryService query,
      IdempotencyService idempotency,
      Clock clock,
      com.dulleobang.job.service.JobCommandService jobs) {
    this.repository = repository;
    this.query = query;
    this.idempotency = idempotency;
    this.clock = clock;
    this.jobs = jobs;
  }

  public StoredResponse create(
      UUID actor, String path, String key, JsonNode input, boolean mobile) {
    query.requireTenant(actor);
    return idempotency.execute(
        actor.toString(),
        "POST",
        path,
        key,
        StrictJson.stringify(input),
        () -> query.requireTenant(actor),
        () -> {
          UUID id = UUID.randomUUID();
          repository.create(
              id,
              actor,
              input.path("name").asText(),
              FurnitureCategoryMapper.canonical(input.path("category").asText()),
              input.get("measuredDimensions"),
              clock.instant());
          return new StoredResponse(
              201,
              StrictJson.stringify(
                  FurnitureMapper.response(query.owned(actor, id, false, false), mobile)),
              Map.of("Location", "/api/v1/furniture/" + id, "ETag", "\"1\""));
        });
  }

  @Transactional
  public FurnitureItem patch(UUID actor, UUID id, String etag, JsonNode input) {
    var item = query.owned(actor, id, true, false);
    ETagParser.requireMatch(etag, item.version());
    repository.patch(
        id,
        input.path("name").asText(item.name()),
        input.path("category").asText(item.category()),
        clock.instant());
    return query.get(actor, id, null);
  }

  @Transactional
  public void delete(UUID actor, UUID id, String etag) {
    var item = query.owned(actor, id, true, true);
    if (item.deletedAt() != null) return;
    ETagParser.requireMatch(etag, item.version());
    if (repository.inUse(id))
      throw new com.dulleobang.common.exception.ApiException(409, "RESOURCE_IN_USE");
    jobs.cancelTarget("FURNITURE", id);
    repository.delete(id, clock.instant());
  }
}
