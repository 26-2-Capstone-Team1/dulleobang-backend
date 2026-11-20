package com.dulleobang.sam3d.service;

import com.dulleobang.common.exception.ApiException;
import com.dulleobang.common.idempotency.StoredResponse;
import com.dulleobang.common.idempotency.service.IdempotencyService;
import com.dulleobang.common.json.StrictJson;
import com.dulleobang.furniture.service.FurnitureQueryService;
import com.dulleobang.sam3d.dto.Sam3dJob;
import com.dulleobang.sam3d.repository.Sam3dRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class Sam3dGenerationService {
  private final Sam3dGateway gateway;
  private final Sam3dRepository repository;
  private final FurnitureQueryService furniture;
  private final IdempotencyService idempotency;

  public Sam3dGenerationService(
      Sam3dGateway gateway,
      Sam3dRepository repository,
      FurnitureQueryService furniture,
      IdempotencyService idempotency) {
    this.gateway = gateway;
    this.repository = repository;
    this.furniture = furniture;
    this.idempotency = idempotency;
  }

  public StoredResponse create(UUID actor, String path, String key, JsonNode input) {
    return idempotency.execute(
        actor.toString(),
        "POST",
        path,
        key,
        StrictJson.stringify(input),
        () -> furniture.requireTenant(actor),
        () -> {
          var job = gateway.generate(input);
          repository.registerJob(actor, job.studioJobId());
          return new StoredResponse(
              202, StrictJson.stringify(job), Map.of("Location", path + "/" + job.studioJobId()));
        });
  }

  public Sam3dJob status(UUID actor, String id) {
    furniture.requireTenant(actor);
    if (!id.matches("[0-9a-f]{32}") || !repository.ownsJob(actor, id))
      throw new ApiException(404, "SAM3D_JOB_NOT_FOUND");
    return gateway.status(id);
  }
}
