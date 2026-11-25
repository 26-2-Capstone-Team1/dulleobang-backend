package com.dulleobang.space.service;

import com.dulleobang.common.exception.ApiException;
import com.dulleobang.job.domain.JobRecord;
import com.dulleobang.job.service.JobResultHandler;
import com.dulleobang.space.repository.SpaceResultRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import org.springframework.stereotype.Service;

@Service
public class RoomResultHandler implements JobResultHandler {
  private final ArtifactCheckService artifacts;
  private final SpaceResultRepository repository;
  private final Clock clock;

  public RoomResultHandler(
      ArtifactCheckService artifacts, SpaceResultRepository repository, Clock clock) {
    this.artifacts = artifacts;
    this.repository = repository;
    this.clock = clock;
  }

  public boolean supports(String type) {
    return type.equals("ROOM_RECONSTRUCTION");
  }

  public JsonNode accept(JobRecord job, JsonNode result) {
    var bundle = result.path("bundle").deepCopy();
    artifacts.bundle(job, bundle);
    for (var object : bundle.path("objects"))
      if (!object.path("ownershipType").asText().equals("UNKNOWN"))
        throw new ApiException(422, "INVALID_INITIAL_CLASSIFICATION");
    ResultDocuments.clean(bundle);
    int version = repository.spaceVersion(job.targetId());
    repository.space(job.targetId(), version, bundle, clock.instant());
    return ResultDocuments.reference("SPACE", job.targetId(), version);
  }
}
