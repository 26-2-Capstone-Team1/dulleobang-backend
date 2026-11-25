package com.dulleobang.job.controller;

import com.dulleobang.common.contract.HttpResponses;
import com.dulleobang.common.contract.JsonInput;
import com.dulleobang.job.service.WorkerJobService;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/internal/ai-jobs/{id}")
public class WorkerJobController {
  private final WorkerJobService jobs;
  private final JsonInput input;

  public WorkerJobController(WorkerJobService jobs, JsonInput input) {
    this.jobs = jobs;
    this.input = input;
  }

  @PostMapping("/claim")
  public ResponseEntity<String> claim(
      Principal worker,
      @PathVariable UUID id,
      @RequestBody(required = false) byte[] body,
      @RequestHeader(value = "Content-Type", required = false) String type) {
    return HttpResponses.json(
        jobs.claim(id, worker.getName(), input.read(body, type, "JobClaimRequest")));
  }

  @PostMapping("/heartbeat")
  public ResponseEntity<String> heartbeat(
      Principal worker,
      @PathVariable UUID id,
      @RequestBody(required = false) byte[] body,
      @RequestHeader(value = "Content-Type", required = false) String type,
      @RequestHeader(value = "X-Job-Lease-Token", defaultValue = "") String lease) {
    return HttpResponses.json(
        jobs.heartbeat(id, worker.getName(), lease, input.read(body, type, "JobHeartbeatRequest")));
  }

  @PostMapping("/artifacts")
  public ResponseEntity<String> artifacts(
      Principal worker,
      @PathVariable UUID id,
      @RequestBody(required = false) byte[] body,
      @RequestHeader(value = "Content-Type", required = false) String type,
      @RequestHeader(value = "X-Job-Lease-Token", defaultValue = "") String lease) {
    return HttpResponses.json(
        jobs.artifacts(id, worker.getName(), lease, input.read(body, type, "ArtifactCreate")));
  }

  @PostMapping("/complete")
  public ResponseEntity<String> complete(
      Principal worker,
      @PathVariable UUID id,
      @RequestBody(required = false) byte[] body,
      @RequestHeader(value = "Content-Type", required = false) String type,
      @RequestHeader(value = "X-Job-Lease-Token", defaultValue = "") String lease) {
    return HttpResponses.json(
        jobs.callback(
            id, worker.getName(), lease, input.read(body, type, "JobCompleteRequest"), true));
  }

  @PostMapping("/fail")
  public ResponseEntity<String> fail(
      Principal worker,
      @PathVariable UUID id,
      @RequestBody(required = false) byte[] body,
      @RequestHeader(value = "Content-Type", required = false) String type,
      @RequestHeader(value = "X-Job-Lease-Token", defaultValue = "") String lease) {
    return HttpResponses.json(
        jobs.callback(
            id, worker.getName(), lease, input.read(body, type, "JobFailRequest"), false));
  }
}
