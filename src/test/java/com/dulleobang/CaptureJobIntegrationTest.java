package com.dulleobang;

import static org.assertj.core.api.Assertions.*;

import com.dulleobang.common.contract.ContractValidator;
import com.dulleobang.common.crypto.Hashes;
import com.dulleobang.common.exception.ApiException;
import com.dulleobang.common.json.StrictJson;
import com.dulleobang.job.service.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class CaptureJobIntegrationTest extends IntegrationTestSupport {
  @Test
  void registersMultipleSourceMeshesPerAttempt() throws Exception {
    String user = activeToken(), worker = token(owner, UUID.randomUUID(), true);
    UUID listing = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO listings(listing_id,owner_id,status) VALUES(?,?,'DRAFT')", listing, owner);
    var accepted =
        zip(
            "/api/listings/" + listing + "/captures",
            P6CaptureFixtures.zip(true, false, false),
            user,
            UUID.randomUUID().toString());
    assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(202);
    String job = StrictJson.parse(accepted.body()).path("jobId").asText();
    String lease = claim(job, worker).path("leaseToken").asText();
    var files = new ArrayList<Map<String, Object>>();
    for (String role : List.of("SCENE", "GEOMETRY", "COLLISION", "SOURCE_MESH", "SOURCE_MESH"))
      files.add(
          Map.of(
              "clientFileId",
              UUID.randomUUID(),
              "role",
              role,
              "byteSize",
              100,
              "sha256",
              "a".repeat(64),
              "contentType",
              role.equals("SCENE")
                  ? "application/octet-stream"
                  : role.equals("GEOMETRY") ? "application/json" : "model/gltf-binary",
              "format",
              role.equals("SCENE")
                  ? "POINT_CLOUD_PLY_V1"
                  : role.equals("GEOMETRY") ? "ROOM_GEOMETRY_V1" : "GLB_V2"));
    var response = action(job, "artifacts", Map.of("files", files), worker, lease);
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    var first = StrictJson.parse(response.body()).path("uploads");
    assertThat(first.size()).isEqualTo(5);
    var replay = action(job, "artifacts", Map.of("files", files), worker, lease);
    assertThat(replay.statusCode()).as(replay.body()).isEqualTo(200);
    var second = StrictJson.parse(replay.body()).path("uploads");
    for (int n = 0; n < 5; n++)
      assertThat(second.get(n).path("fileId")).isEqualTo(first.get(n).path("fileId"));
  }

  @Test
  void queueRequiresWorkerAndHidesClaimedJobs() throws Exception {
    String path = "/api/v1/internal/ai-jobs";
    String user = activeToken(), worker = token(owner, UUID.randomUUID(), true);
    assertThat(request("GET", "/", null).statusCode()).isEqualTo(200);
    assertThat(request("GET", path, null).statusCode()).isEqualTo(401);
    assertThat(request("GET", path, user).statusCode()).isEqualTo(401);
    assertThat(request("GET", path + "?limit=50", worker).statusCode()).isEqualTo(200);
    assertThat(request("GET", path + "?limit=51", worker).statusCode()).isEqualTo(400);
    var accepted = capture(furniture(user), user);
    maintenance.maintain();
    var response = request("GET", path, worker);
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    var jobs = StrictJson.parse(response.body()).path("items");
    assertThat(jobs.size()).isEqualTo(1);
    assertThat(jobs.get(0).path("jobId").asText()).isEqualTo(accepted.path("jobId").asText());
    claim(accepted.path("jobId").asText(), worker);
    assertThat(StrictJson.parse(request("GET", path, worker).body()).path("items").isEmpty())
        .isTrue();
  }

  @Autowired ContractValidator contracts;
  @Autowired JobCommandService commands;
  @Autowired JobMaintenanceService maintenance;
  @Autowired PlatformTransactionManager manager;

  UUID furniture(String token) throws Exception {
    var response =
        json(
            "POST",
            "/api/v1/furniture",
            Map.of("name", "촬영", "category", "DESK"),
            token,
            "Idempotency-Key",
            UUID.randomUUID().toString());
    assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    return UUID.fromString(StrictJson.parse(response.body()).path("furnitureId").asText());
  }

  URI local(String address) {
    var source = URI.create(address);
    return URI.create(
        "http://127.0.0.1:" + port + source.getRawPath() + "?" + source.getRawQuery());
  }

  HttpResponse<byte[]> put(JsonNode grant, byte[] bytes) throws Exception {
    var builder =
        HttpRequest.newBuilder(local(grant.path("url").asText()))
            .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes));
    grant
        .path("headers")
        .fields()
        .forEachRemaining(e -> builder.header(e.getKey(), e.getValue().asText()));
    return http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
  }

  HttpResponse<String> zip(String path, byte[] bytes, String token, String key) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", key)
            .header("Content-Type", "application/zip")
            .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  JsonNode capture(UUID id, String token) throws Exception {
    var response =
        zip(
            "/api/furniture/" + id + "/captures",
            P6CaptureFixtures.zip(false, false, false),
            token,
            UUID.randomUUID().toString());
    assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
    contracts.validate("CaptureAccepted", response.body());
    return StrictJson.parse(response.body());
  }

  JsonNode claim(String id, String worker) throws Exception {
    var response =
        json(
            "POST",
            "/api/v1/internal/ai-jobs/" + id + "/claim",
            Map.of("workerId", "worker"),
            worker);
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    contracts.validate("JobAssignment", response.body());
    return StrictJson.parse(response.body());
  }

  HttpResponse<String> action(String id, String action, Object body, String worker, String lease)
      throws Exception {
    return json(
        "POST",
        "/api/v1/internal/ai-jobs/" + id + "/" + action,
        body,
        worker,
        "X-Job-Lease-Token",
        lease);
  }

  Map<String, Object> failure(String code, boolean retryable) {
    return Map.of(
        "error", Map.of("code", code, "message", "fixture failure", "retryable", retryable));
  }

  @Test
  void checksWorkerLease() throws Exception {
    String user = activeToken(), worker = token(owner, UUID.randomUUID(), true);
    UUID id = furniture(user);
    byte[] raw = P6CaptureFixtures.zip(false, false, false);
    String path = "/api/furniture/" + id + "/captures", key = UUID.randomUUID().toString();
    var accepted = zip(path, raw, user, key);
    assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(202);
    assertThat(zip(path, raw, user, key).body()).isEqualTo(accepted.body());
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM captures", Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_jobs", Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM job_outbox", Integer.class)).isEqualTo(1);
    String job = StrictJson.parse(accepted.body()).path("jobId").asText();
    assertThat(
            json(
                    "POST",
                    "/api/v1/internal/ai-jobs/" + job + "/claim",
                    Map.of("workerId", "worker"),
                    user)
                .statusCode())
        .isEqualTo(401);
    assertThat(
            json(
                    "POST",
                    "/api/v1/internal/ai-jobs/" + job + "/claim",
                    Map.of("workerId", "another-worker"),
                    worker)
                .statusCode())
        .isEqualTo(403);
    var assignment = claim(job, worker);
    String lease = assignment.path("leaseToken").asText();
    assertThat(
            json(
                    "POST",
                    "/api/v1/internal/ai-jobs/" + job + "/claim",
                    Map.of("workerId", "worker"),
                    worker)
                .statusCode())
        .isEqualTo(409);
    var manifest = assignment.path("input").path("manifest");
    var downloaded =
        http.send(
            HttpRequest.newBuilder(local(manifest.path("url").asText())).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray());
    assertThat(downloaded.statusCode()).isEqualTo(200);
    assertThat(Hashes.sha256(downloaded.body()))
        .isEqualTo(manifest.path("asset").path("sha256").asText());
    var normalized = StrictJson.parse(downloaded.body());
    assertThat(normalized.path("frames").get(0).path("cameraToWorld").get(12).asDouble()).isZero();
    assertThat(normalized.path("frames").get(1).path("timestampUs").asLong()).isEqualTo(100000);
    var beat = Map.of("progressPercent", 30, "stage", "RECONSTRUCTING", "refreshInputUrls", true);
    assertThat(action(job, "heartbeat", beat, worker, "wrong").statusCode()).isEqualTo(409);
    var heartbeat = action(job, "heartbeat", beat, worker, lease);
    assertThat(heartbeat.statusCode()).as(heartbeat.body()).isEqualTo(200);
    contracts.validate("JobHeartbeat", heartbeat.body());
    assertThat(
            action(
                    job,
                    "heartbeat",
                    Map.of(
                        "progressPercent",
                        29,
                        "stage",
                        "RECONSTRUCTING",
                        "refreshInputUrls",
                        false),
                    worker,
                    lease)
                .statusCode())
        .isEqualTo(422);
    byte[] artifact = new byte[] {1, 2, 3};
    var files = new ArrayList<Map<String, Object>>();
    for (String role : List.of("MODEL", "COLLISION"))
      files.add(
          Map.of(
              "clientFileId",
              UUID.randomUUID(),
              "role",
              role,
              "contentType",
              "model/gltf-binary",
              "format",
              "GLB_V2",
              "byteSize",
              artifact.length,
              "sha256",
              Hashes.sha256(artifact)));
    var grants = action(job, "artifacts", Map.of("files", files), worker, lease);
    assertThat(grants.statusCode()).as(grants.body()).isEqualTo(200);
    var grant = StrictJson.parse(grants.body()).path("uploads").get(0);
    assertThat(put(grant, artifact).statusCode()).isEqualTo(200);
    var canceled = json("POST", "/api/v1/ai-jobs/" + job + "/cancel", null, user);
    assertThat(canceled.statusCode()).as(canceled.body()).isEqualTo(200);
    contracts.validate("Job", canceled.body());
    assertThat(put(grant, artifact).statusCode()).isEqualTo(409);
    assertThat(action(job, "heartbeat", beat, worker, lease).statusCode()).isEqualTo(409);
  }

  @Test
  void rollsBackInvalidZip() throws Exception {
    String user = activeToken();
    UUID id = furniture(user);
    String path = "/api/furniture/" + id + "/captures", key = UUID.randomUUID().toString();
    for (byte[] bad :
        List.of(
            P6CaptureFixtures.zip(false, true, false),
            P6CaptureFixtures.zip(false, false, true),
            new byte[] {1, 2, 3})) {
      var response = zip(path, bad, user, key);
      assertThat(response.statusCode()).as(response.body()).isEqualTo(422);
      for (String table : List.of("captures", "ai_jobs", "upload_sessions", "assets", "job_outbox"))
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class))
            .as(table)
            .isZero();
    }
    assertThat(zip(path, P6CaptureFixtures.zip(false, false, false), user, key).statusCode())
        .isEqualTo(202);
  }

  @Test
  void checksRetryReceipts() throws Exception {
    String user = activeToken(), worker = token(owner, UUID.randomUUID(), true);
    var accepted = capture(furniture(user), user);
    String job = accepted.path("jobId").asText();
    String previousLease = null;
    for (int attempt = 1; attempt <= 3; attempt++) {
      jdbc.update(
          "UPDATE ai_jobs SET due_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE job_id=?",
          UUID.fromString(job));
      var assignment = claim(job, worker);
      assertThat(assignment.path("attemptNo").asInt()).isEqualTo(attempt);
      String lease = assignment.path("leaseToken").asText();
      if (previousLease != null)
        assertThat(
                action(job, "fail", failure("DEPENDENCY_UNAVAILABLE", false), worker, previousLease)
                    .statusCode())
            .isEqualTo(409);
      var failed = action(job, "fail", failure("DEPENDENCY_UNAVAILABLE", false), worker, lease);
      assertThat(failed.statusCode()).as(failed.body()).isEqualTo(200);
      assertThat(StrictJson.parse(failed.body()).path("status").asText())
          .isEqualTo(attempt < 3 ? "RETRY_WAIT" : "FAILED");
      assertThat(StrictJson.parse(failed.body()).path("error").path("retryable").asBoolean())
          .isTrue();
      assertThat(
              StrictJson.parse(
                  action(job, "fail", failure("DEPENDENCY_UNAVAILABLE", false), worker, lease)
                      .body()))
          .isEqualTo(StrictJson.parse(failed.body()));
      assertThat(action(job, "fail", failure("INTERNAL_ERROR", true), worker, lease).statusCode())
          .isEqualTo(409);
      previousLease = lease;
    }
    String retryPath = "/api/v1/ai-jobs/" + job + "/retries",
        retryKey = UUID.randomUUID().toString();
    var retried = json("POST", retryPath, null, user, "Idempotency-Key", retryKey);
    assertThat(retried.statusCode()).as(retried.body()).isEqualTo(202);
    assertThat(json("POST", retryPath, null, user, "Idempotency-Key", retryKey).body())
        .isEqualTo(retried.body());
    String second = StrictJson.parse(retried.body()).path("jobId").asText();
    var secondLease = claim(second, worker).path("leaseToken").asText();
    var terminal = action(second, "fail", failure("INVALID_ARTIFACT", true), worker, secondLease);
    assertThat(terminal.statusCode()).as(terminal.body()).isEqualTo(200);
    assertThat(StrictJson.parse(terminal.body()).path("status").asText()).isEqualTo("FAILED");
    assertThat(StrictJson.parse(terminal.body()).path("error").path("retryable").asBoolean())
        .isFalse();
  }

  @Test
  void retriesExpiredLease() throws Exception {
    String user = activeToken(), worker = token(owner, UUID.randomUUID(), true);
    String job = capture(furniture(user), user).path("jobId").asText();
    String lease = claim(job, worker).path("leaseToken").asText();
    jdbc.update(
        "UPDATE ai_jobs SET lease_expires_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE job_id=?",
        UUID.fromString(job));
    assertThat(action(job, "fail", failure("INVALID_ARTIFACT", false), worker, lease).statusCode())
        .isEqualTo(409);
    maintenance.maintain();
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM ai_jobs WHERE job_id=?", String.class, UUID.fromString(job)))
        .isEqualTo("RETRY_WAIT");
    assertThat(
            json(
                    "POST",
                    "/api/v1/internal/ai-jobs/" + job + "/claim",
                    Map.of("workerId", "worker"),
                    worker)
                .statusCode())
        .isEqualTo(409);
    jdbc.update(
        "UPDATE ai_jobs SET due_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE job_id=?",
        UUID.fromString(job));
    jdbc.update(
        "UPDATE job_outbox SET available_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE"
            + " delivered_at IS NULL");
    maintenance.maintain();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM job_queue", Integer.class)).isEqualTo(1);
    assertThat(claim(job, worker).path("attemptNo").asInt()).isEqualTo(2);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM job_queue", Integer.class)).isZero();
  }

  @Test
  void cleansDeletedJobs() throws Exception {
    String user = activeToken();
    UUID id = furniture(user);
    String job = capture(id, user).path("jobId").asText();
    var get = json("GET", "/api/v1/furniture/" + id, null, user);
    var deleted =
        json(
            "DELETE",
            "/api/v1/furniture/" + id,
            null,
            user,
            "If-Match",
            get.headers().firstValue("ETag").orElseThrow());
    assertThat(deleted.statusCode()).as(deleted.body()).isEqualTo(204);
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM ai_jobs WHERE job_id=?", String.class, UUID.fromString(job)))
        .isEqualTo("CANCELED");
    assertThat(json("GET", "/api/v1/ai-jobs/" + job, null, user).statusCode()).isEqualTo(404);
    UUID orphan = furniture(user), healthy = furniture(user);
    var tx = new TransactionTemplate(manager);
    var orphanJob =
        tx.execute(
            s ->
                commands.create(
                    owner, "FURNITURE_RECONSTRUCTION", "FURNITURE", orphan, null, null, null));
    var healthyJob =
        tx.execute(
            s ->
                commands.create(
                    owner, "FURNITURE_RECONSTRUCTION", "FURNITURE", healthy, null, null, null));
    jdbc.update("UPDATE furniture SET deleted_at=CURRENT_TIMESTAMP WHERE furniture_id=?", orphan);
    maintenance.maintain();
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM ai_jobs WHERE job_id=?", String.class, orphanJob.id()))
        .isEqualTo("CANCELED");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM job_queue WHERE job_id=?", Integer.class, healthyJob.id()))
        .isEqualTo(1);
    jdbc.update(
        "UPDATE ai_jobs SET created_at=CURRENT_TIMESTAMP-INTERVAL '61 minutes' WHERE job_id=?",
        healthyJob.id());
    maintenance.maintain();
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM ai_jobs WHERE job_id=?", String.class, healthyJob.id()))
        .isEqualTo("FAILED");
  }

  @Test
  void limitsConcurrentZips() throws Exception {
    String user = activeToken();
    var ids = new ArrayList<UUID>();
    for (int n = 0; n < 4; n++) ids.add(furniture(user));
    byte[] raw = P6CaptureFixtures.zip(false, false, false);
    var gate = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(4);
    try {
      var futures = new ArrayList<Future<Integer>>();
      for (UUID id : ids)
        futures.add(
            executor.submit(
                () -> {
                  gate.await();
                  return zip(
                          "/api/furniture/" + id + "/captures",
                          raw,
                          user,
                          UUID.randomUUID().toString())
                      .statusCode();
                }));
      gate.countDown();
      var results = new ArrayList<Integer>();
      for (var future : futures) results.add(future.get(30, TimeUnit.SECONDS));
      assertThat(Collections.frequency(results, 202)).isEqualTo(3);
      assertThat(Collections.frequency(results, 409)).isEqualTo(1);
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM captures", Integer.class)).isEqualTo(3);
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM upload_sessions", Integer.class))
          .isEqualTo(3);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void runsPythonWorker() throws Exception {
    String user = activeToken();
    var capture = capture(furniture(user), user);
    var process =
        new ProcessBuilder(
                System.getenv().getOrDefault("V2_PYTHON", "python"), "contract-tests/worker.py")
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start();
    try (var input = process.getOutputStream()) {
      input.write(
          StrictJson.stringify(
                  Map.of(
                      "baseUrl",
                      "http://127.0.0.1:" + port,
                      "token",
                      token(owner, UUID.randomUUID(), true),
                      "jobId",
                      capture.path("jobId").asText()))
              .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    if (!process.waitFor(60, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      fail("Python WorkerClient timed out");
    }
    assertThat(process.exitValue()).isZero();
  }

  @Test
  void expiresCaptureInputs() throws Exception {
    String user = activeToken(), worker = token(owner, UUID.randomUUID(), true);
    UUID target = furniture(user);
    byte[] image = P6CaptureFixtures.jpeg();
    var raw = new LinkedHashMap<UUID, byte[]>();
    var files = new ArrayList<Map<String, Object>>();
    var frames = new ArrayList<Map<String, Object>>();
    for (int n = 0; n < 12; n++) {
      UUID client = UUID.randomUUID();
      raw.put(client, image);
      files.add(P6CaptureFixtures.descriptor(client, "frame_" + n + ".jpg", "image/jpeg", image));
      frames.add(
          Map.of(
              "imageClientFileId",
              client,
              "timestampUs",
              n * 100000,
              "widthPx",
              640,
              "heightPx",
              480,
              "intrinsics",
              Map.of("fx", 500, "fy", 500, "cx", 320, "cy", 240),
              "cameraToWorld",
              P6CaptureFixtures.pose(n * .1),
              "trackingState",
              "NORMAL"));
    }
    var manifest =
        Map.of(
            "schemaVersion",
            "1.0",
            "platform",
            "ARKIT",
            "coordinateSystem",
            "RH_Y_UP_Z_BACK",
            "unit",
            "METER",
            "matrixLayout",
            "COLUMN_MAJOR",
            "captureStartedAt",
            com.dulleobang.common.json.ApiTime.format(java.time.Instant.now()),
            "frames",
            frames);
    byte[] document =
        StrictJson.stringify(manifest).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    UUID client = UUID.randomUUID();
    raw.put(client, document);
    files.add(P6CaptureFixtures.descriptor(client, "camera.json", "application/json", document));
    var opened =
        json(
            "POST",
            "/api/v1/upload-sessions",
            Map.of(
                "purpose",
                "FURNITURE_CAPTURE",
                "targetType",
                "FURNITURE",
                "targetId",
                target,
                "files",
                files),
            user,
            "Idempotency-Key",
            UUID.randomUUID().toString());
    assertThat(opened.statusCode()).as(opened.body()).isEqualTo(201);
    var session = StrictJson.parse(opened.body());
    for (var grant : session.path("uploads"))
      assertThat(
              put(grant, raw.get(UUID.fromString(grant.path("clientFileId").asText())))
                  .statusCode())
          .isEqualTo(200);
    String sessionId = session.path("session").path("uploadSessionId").asText();
    var verified = json("POST", "/api/v1/upload-sessions/" + sessionId + "/complete", null, user);
    assertThat(verified.statusCode()).as(verified.body()).isEqualTo(200);
    String path = "/api/v1/furniture/" + target + "/captures", key = UUID.randomUUID().toString();
    var body = Map.of("uploadSessionId", sessionId);
    var submitted = json("POST", path, body, user, "Idempotency-Key", key);
    assertThat(submitted.statusCode()).as(submitted.body()).isEqualTo(202);
    assertThat(json("POST", path, body, user, "Idempotency-Key", key).body())
        .isEqualTo(submitted.body());
    assertThat(
            json("POST", path, body, user, "Idempotency-Key", UUID.randomUUID().toString())
                .statusCode())
        .isEqualTo(409);
    var accepted = StrictJson.parse(submitted.body());
    String capture = accepted.path("captureId").asText(), job = accepted.path("jobId").asText();
    var detail = json("GET", "/api/v1/captures/" + capture, null, user);
    assertThat(detail.statusCode()).as(detail.body()).isEqualTo(200);
    contracts.validate("Capture", detail.body());
    assertThat(StrictJson.parse(detail.body()).path("imageAssets")).hasSize(12);
    String lease = claim(job, worker).path("leaseToken").asText();
    var terminal = action(job, "fail", failure("INVALID_ARTIFACT", false), worker, lease);
    assertThat(terminal.statusCode()).as(terminal.body()).isEqualTo(200);
    jdbc.update(
        "UPDATE ai_jobs SET updated_at=CURRENT_TIMESTAMP-INTERVAL '31 days' WHERE job_id=?",
        UUID.fromString(job));
    assertThat(json("GET", "/api/v1/captures/" + capture, null, user).statusCode()).isEqualTo(410);
    maintenance.maintain();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM assets WHERE session_id=? AND stored",
                Integer.class,
                UUID.fromString(sessionId)))
        .isZero();
  }

  @Test
  void limitsConcurrentJobs() throws Exception {
    String user = activeToken();
    var ids = new ArrayList<UUID>();
    for (int n = 0; n < 4; n++) ids.add(furniture(user));
    var gate = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(4);
    try {
      var results = new ArrayList<Future<String>>();
      for (UUID target : ids)
        results.add(
            executor.submit(
                () -> {
                  gate.await();
                  try {
                    return new TransactionTemplate(manager)
                        .execute(
                            s ->
                                commands.create(
                                    owner,
                                    "FURNITURE_RECONSTRUCTION",
                                    "FURNITURE",
                                    target,
                                    null,
                                    null,
                                    null))
                        .status();
                  } catch (ApiException ex) {
                    return ex.code();
                  }
                }));
      gate.countDown();
      var statuses = new ArrayList<String>();
      for (var result : results) statuses.add(result.get(20, TimeUnit.SECONDS));
      assertThat(Collections.frequency(statuses, "QUEUED")).isEqualTo(3);
      assertThat(Collections.frequency(statuses, "ACTIVE_JOB_LIMIT")).isEqualTo(1);
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM job_outbox", Integer.class))
          .isEqualTo(3);
      UUID active = jdbc.queryForObject("SELECT target_id FROM ai_jobs LIMIT 1", UUID.class);
      jdbc.update("UPDATE ai_jobs SET status='CANCELED' WHERE target_id<>?", active);
      assertThatThrownBy(
              () ->
                  new TransactionTemplate(manager)
                      .execute(
                          s ->
                              commands.create(
                                  owner,
                                  "FURNITURE_RECONSTRUCTION",
                                  "FURNITURE",
                                  active,
                                  null,
                                  null,
                                  null)))
          .isInstanceOfSatisfying(
              ApiException.class, ex -> assertThat(ex.code()).isEqualTo("ACTIVE_JOB_EXISTS"));
    } finally {
      executor.shutdownNow();
    }
  }
}
