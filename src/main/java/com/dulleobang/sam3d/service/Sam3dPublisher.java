package com.dulleobang.sam3d.service;

import com.dulleobang.common.exception.ApiException;
import com.dulleobang.common.idempotency.StoredResponse;
import com.dulleobang.common.json.*;
import com.dulleobang.furniture.mapper.FurnitureMapper;
import com.dulleobang.furniture.repository.FurnitureRepository;
import com.dulleobang.furniture.service.FurnitureQueryService;
import com.dulleobang.sam3d.dto.Sam3dModel;
import com.dulleobang.sam3d.repository.Sam3dRepository;
import com.dulleobang.sam3d.validation.BoxMesh;
import com.dulleobang.space.repository.SpaceResultRepository;
import com.dulleobang.upload.service.SignedUrlService;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class Sam3dPublisher {
  private final FurnitureRepository furniture;
  private final FurnitureQueryService query;
  private final Sam3dRepository imports;
  private final Sam3dAssetService assets;
  private final SpaceResultRepository models;
  private final SignedUrlService metadata;
  private final Clock clock;

  public Sam3dPublisher(
      FurnitureRepository furniture,
      FurnitureQueryService query,
      Sam3dRepository imports,
      Sam3dAssetService assets,
      SpaceResultRepository models,
      SignedUrlService metadata,
      Clock clock) {
    this.furniture = furniture;
    this.query = query;
    this.imports = imports;
    this.assets = assets;
    this.models = models;
    this.metadata = metadata;
    this.clock = clock;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public StoredResponse save(UUID actor, JsonNode input, Sam3dModel prepared, boolean mobile) {
    String job = input.path("studioJobId").asText();
    imports.lock(actor, job);
    if (imports.find(actor, job).isPresent()) return existing(actor, input, mobile);
    UUID id = UUID.randomUUID();
    var now = clock.instant();
    furniture.create(
        id,
        actor,
        input.path("name").asText(),
        input.path("category").asText(),
        prepared.dimensions(),
        now);
    var model = assets.save(actor, id, "MODEL", prepared.bytes());
    var collision = assets.save(actor, id, "COLLISION", BoxMesh.create(prepared.dimensions()));
    var document =
        StrictJson.parse(
            StrictJson.stringify(
                Map.of(
                    "modelVersion",
                    1,
                    "createdAt",
                    ApiTime.format(now),
                    "modelAsset",
                    metadata.asset(model),
                    "collisionAsset",
                    metadata.asset(collision),
                    "dimensions",
                    prepared.dimensions(),
                    "boundingBox",
                    prepared.bounds())));
    models.model(id, 1, document, now);
    imports.save(id, actor, job, StrictJson.digest(StrictJson.stringify(input)), now);
    return response(actor, id, job, mobile, 201);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public StoredResponse existing(UUID actor, JsonNode input, boolean mobile) {
    String job = input.path("studioJobId").asText();
    imports.lock(actor, job);
    var row =
        imports.find(actor, job).orElseThrow(() -> new ApiException(404, "RESOURCE_NOT_FOUND"));
    if (!row.hash().equals(StrictJson.digest(StrictJson.stringify(input))))
      throw new ApiException(409, "SAM3D_ALREADY_IMPORTED");
    return response(actor, row.furniture(), job, mobile, 200);
  }

  private StoredResponse response(UUID actor, UUID id, String job, boolean mobile, int status) {
    var item = query.get(actor, id, null);
    return new StoredResponse(
        status,
        StrictJson.stringify(
            Map.of(
                "furniture",
                FurnitureMapper.response(item, mobile),
                "source",
                Map.of(
                    "studioJobId",
                    job,
                    "engine",
                    "SAM 3D Objects",
                    "collisionApproximation",
                    "BOUNDING_BOX"))),
        Map.of("Location", "/api/v1/furniture/" + id, "ETag", "\"" + item.version() + "\""));
  }
}
