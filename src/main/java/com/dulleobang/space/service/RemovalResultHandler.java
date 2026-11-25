package com.dulleobang.space.service;

import com.dulleobang.job.domain.JobRecord;
import com.dulleobang.job.service.JobResultHandler;
import com.dulleobang.space.repository.SpaceResultRepository;
import com.dulleobang.space.validation.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

@Service
public class RemovalResultHandler implements JobResultHandler {
  private final ArtifactCheckService artifacts;
  private final SpaceResultRepository repository;
  private final RemovalResultPolicy policy;

  public RemovalResultHandler(
      ArtifactCheckService artifacts,
      SpaceResultRepository repository,
      RemovalResultPolicy policy) {
    this.artifacts = artifacts;
    this.repository = repository;
    this.policy = policy;
  }

  public boolean supports(String type) {
    return type.equals("ROOM_OBJECT_REMOVAL");
  }

  public JsonNode accept(JobRecord job, JsonNode result) {
    var bundle = result.path("bundle").deepCopy();
    var nodes = artifacts.bundle(job, bundle);
    policy.validate(
        job.targetId(),
        result,
        repository.source(job.targetId()),
        repository.removed(job.targetId()),
        nodes);
    ResultDocuments.clean(bundle);
    repository.removal(job.targetId(), bundle);
    return ResultDocuments.reference("ROOM_STATE", job.targetId(), 1);
  }
}
