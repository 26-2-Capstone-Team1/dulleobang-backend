package com.dulleobang.sam3d.service;

import com.dulleobang.common.exception.ApiException;
import com.dulleobang.common.idempotency.StoredResponse;
import com.dulleobang.common.idempotency.service.IdempotencyService;
import com.dulleobang.common.json.StrictJson;
import com.dulleobang.furniture.service.FurnitureQueryService;
import com.dulleobang.sam3d.repository.Sam3dRepository;
import com.dulleobang.space.validation.GlbValidator;
import com.dulleobang.upload.service.AssetResponseRefresher;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Service;

@Service
public class Sam3dImportService {
  private final Sam3dGateway gateway;
  private final Sam3dPublisher publisher;
  private final Sam3dRepository repository;
  private final FurnitureQueryService furniture;
  private final IdempotencyService idempotency;
  private final AssetResponseRefresher responses;
  private final GlbValidator glb;
  private final Semaphore slots = new Semaphore(2);

  public Sam3dImportService(
      Sam3dGateway gateway,
      Sam3dPublisher publisher,
      Sam3dRepository repository,
      FurnitureQueryService furniture,
      IdempotencyService idempotency,
      AssetResponseRefresher responses,
      GlbValidator glb) {
    this.gateway = gateway;
    this.publisher = publisher;
    this.repository = repository;
    this.furniture = furniture;
    this.idempotency = idempotency;
    this.responses = responses;
    this.glb = glb;
  }

  public StoredResponse save(UUID actor, String path, String key, JsonNode input, boolean mobile) {
    furniture.requireTenant(actor);
    String body = StrictJson.stringify(input);
    var replay = idempotency.lookup(actor.toString(), "POST", path, key, body);
    if (replay.isPresent()) return responses.refresh(replay.get());
    if (repository.find(actor, input.path("studioJobId").asText()).isPresent())
      return idempotency.execute(
          actor.toString(),
          "POST",
          path,
          key,
          body,
          () -> furniture.requireTenant(actor),
          () -> publisher.existing(actor, input, mobile));
    if (!slots.tryAcquire()) throw new ApiException(503, "SAM3D_IMPORT_BUSY");
    try {
      var prepared = gateway.fetch(input.path("studioJobId").asText());
      glb.validate(prepared.bytes(), false, prepared.bounds());
      return idempotency.execute(
          actor.toString(),
          "POST",
          path,
          key,
          body,
          () -> furniture.requireTenant(actor),
          () -> publisher.save(actor, input, prepared, mobile));
    } finally {
      slots.release();
    }
  }
}
