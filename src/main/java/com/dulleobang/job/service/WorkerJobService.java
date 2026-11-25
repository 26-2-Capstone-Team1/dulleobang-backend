package com.dulleobang.job.service;

import com.dulleobang.common.exception.ApiException;
import com.dulleobang.common.json.*;
import com.dulleobang.job.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkerJobService {
  private final JobRepository repository;
  private final JobAccessService access;
  private final JobLeaseService leases;
  private final JobInputService inputs;
  private final JobArtifactService artifacts;
  private final JobCommandService commands;
  private final JobReceiptRepository receipts;
  private final List<JobResultHandler> results;

  public WorkerJobService(
      JobRepository repository,
      JobAccessService access,
      JobLeaseService leases,
      JobInputService inputs,
      JobArtifactService artifacts,
      JobCommandService commands,
      JobReceiptRepository receipts,
      List<JobResultHandler> results) {
    this.repository = repository;
    this.access = access;
    this.leases = leases;
    this.inputs = inputs;
    this.artifacts = artifacts;
    this.commands = commands;
    this.receipts = receipts;
    this.results = List.copyOf(results);
  }

  @Transactional
  public Object claim(UUID id, String worker, JsonNode input) {
    var job = access.get(id, true);
    String lease = leases.claim(job, worker, input.path("workerId").asText());
    job = repository.find(id, false).orElseThrow();
    return Map.of(
        "jobId",
        id,
        "type",
        job.type(),
        "attemptNo",
        job.attempt(),
        "leaseToken",
        lease,
        "leaseExpiresAt",
        ApiTime.format(job.leaseExpiresAt()),
        "input",
        inputs.input(job));
  }

  @Transactional
  public Object heartbeat(UUID id, String worker, String lease, JsonNode input) {
    var job = access.get(id, true);
    leases.require(job, worker, lease);
    var until =
        leases.heartbeat(job, input.path("progressPercent").asInt(), input.path("stage").asText());
    var response = new LinkedHashMap<String, Object>();
    response.put("leaseExpiresAt", ApiTime.format(until));
    if (input.path("refreshInputUrls").asBoolean()) response.put("input", inputs.input(job));
    return response;
  }

  @Transactional
  public Object artifacts(UUID id, String worker, String lease, JsonNode input) {
    var job = access.get(id, true);
    leases.require(job, worker, lease);
    return artifacts.create(job, input);
  }

  @Transactional
  public Object callback(UUID id, String worker, String lease, JsonNode input, boolean complete) {
    var job = access.get(id, true);
    String operation = complete ? "complete" : "fail",
        body = StrictJson.stringify(input),
        leaseHash = JobLeaseService.digest(lease);
    var replay = receipts.replay(job, operation, worker, leaseHash, body);
    if (replay.isPresent()) return replay.get();
    leases.require(job, worker, lease);
    Object response;
    if (complete) {
      if (!input.path("type").asText().equals(job.type()))
        throw new ApiException(422, "JOB_RESULT_TYPE_MISMATCH");
      String type = job.type();
      var handler =
          results.stream()
              .filter(h -> h.supports(type))
              .findFirst()
              .orElseThrow(() -> new ApiException(501, "FEATURE_NOT_IMPLEMENTED"));
      var result = handler.accept(job, input);
      leases.require(repository.find(id, false).orElseThrow(), worker, lease);
      response = commands.finish(job, "COMPLETED", result);
    } else
      response =
          commands.fail(
              job,
              input.path("error").path("code").asText(),
              input.path("error").path("message").asText());
    var json = StrictJson.parse(StrictJson.stringify(response));
    receipts.save(job, operation, worker, leaseHash, body, json);
    return json;
  }
}
