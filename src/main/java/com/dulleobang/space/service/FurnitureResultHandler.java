package com.dulleobang.space.service;

import com.dulleobang.common.json.ApiTime;
import com.dulleobang.job.domain.JobRecord;
import com.dulleobang.job.service.JobResultHandler;
import com.dulleobang.space.repository.SpaceResultRepository;
import com.dulleobang.space.validation.*;
import com.dulleobang.space.validation.BoundsValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import org.springframework.stereotype.Service;

@Service
public class FurnitureResultHandler implements JobResultHandler {
  private final ArtifactCheckService artifacts;
  private final SpaceResultRepository repository;
  private final Clock clock;

  public FurnitureResultHandler(
      ArtifactCheckService artifacts, SpaceResultRepository repository, Clock clock) {
    this.artifacts = artifacts;
    this.repository = repository;
    this.clock = clock;
  }

  public boolean supports(String type) {
    return type.equals("FURNITURE_RECONSTRUCTION");
  }

  public JsonNode accept(JobRecord job, JsonNode result) {
    artifacts.asset(job, result.path("modelAsset"), "MODEL", result.path("boundingBox"));
    artifacts.asset(job, result.path("collisionAsset"), "COLLISION", null);
    BoundsValidator.box(result.path("dimensions"), result.path("boundingBox"));
    int version = repository.modelVersion(job.targetId());
    var model = (ObjectNode) result.deepCopy();
    model.remove("type");
    model.put("modelVersion", version).put("createdAt", ApiTime.format(clock.instant()));
    ResultDocuments.clean(model);
    repository.model(job.targetId(), version, model, clock.instant());
    return ResultDocuments.reference("FURNITURE_MODEL", job.targetId(), version);
  }
}
