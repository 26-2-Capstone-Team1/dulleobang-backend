package com.dulleobang.infrastructure.sam3d;

import com.dulleobang.common.exception.ApiException;
import com.dulleobang.common.json.StrictJson;
import com.dulleobang.sam3d.dto.Sam3dJob;
import com.dulleobang.sam3d.dto.Sam3dJob.Status;
import com.dulleobang.sam3d.dto.Sam3dModel;
import com.dulleobang.sam3d.service.Sam3dGateway;
import com.dulleobang.sam3d.validation.Sam3dPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class Sam3dHttp implements Sam3dGateway {
  private static final String JOBS = "/api/jobs";
  private static final int JOB_RESPONSE_BYTES = 65536;
  private final boolean enabled;
  private final String base;
  private final Sam3dPolicy policy;
  private final HttpClient client =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(3))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  public Sam3dHttp(
      @Value("${dulleobang.sam3d.enabled:false}") boolean enabled,
      @Value("${dulleobang.sam3d.url:http://127.0.0.1:18766}") String base,
      Sam3dPolicy policy) {
    this.enabled = enabled;
    this.base = origin(base);
    this.policy = policy;
  }

  private String origin(String value) {
    var uri = URI.create(value);
    boolean secure = "https".equals(uri.getScheme());
    if ((!secure
            && !("http".equals(uri.getScheme())
                && Set.of("127.0.0.1", "localhost", "[::1]").contains(uri.getHost())))
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null
        || uri.getHost() == null
        || !Set.of("", "/").contains(uri.getPath()))
      throw new IllegalArgumentException("Use a loopback HTTP tunnel or an HTTPS SAM3D origin");
    return value.replaceAll("/$", "");
  }

  @Override
  public Sam3dJob generate(JsonNode input) {
    requireEnabled();
    var request =
        request(JOBS)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(StrictJson.stringify(input)))
            .build();
    return parseJob(json(send(request, JOB_RESPONSE_BYTES, 202)));
  }

  @Override
  public Sam3dJob status(String id) {
    return parseJob(readJob(id));
  }

  @Override
  public Sam3dModel fetch(String id) {
    var job = readJob(id);
    switch (parseJob(job).status()) {
      case QUEUED, RUNNING -> throw new ApiException(409, "SAM3D_JOB_NOT_READY");
      case FAILED -> throw new ApiException(422, "SAM3D_JOB_FAILED");
      case COMPLETED -> {}
    }
    String path = JOBS + "/" + id;
    var dims = policy.dimensions(job, json(get(path + "/generation.json", 1048576)));
    return new Sam3dModel(get(path + "/model.glb", 64 * 1024 * 1024), dims, policy.bounds(dims));
  }

  private void requireEnabled() {
    if (!enabled) throw new ApiException(503, "SAM3D_NOT_CONFIGURED");
  }

  private JsonNode readJob(String id) {
    requireEnabled();
    if (!id.matches("[0-9a-f]{32}")) throw new ApiException(422, "VALIDATION_FAILED");
    var job = json(get(JOBS + "/" + id, JOB_RESPONSE_BYTES));
    if (!job.path("id").asText().equals(id)) throw new ApiException(502, "INVALID_SAM3D_RESPONSE");
    return job;
  }

  private Sam3dJob parseJob(JsonNode response) {
    String id = response.path("id").asText();
    if (!id.matches("[0-9a-f]{32}")) throw new ApiException(502, "INVALID_SAM3D_RESPONSE");
    Status status =
        switch (response.path("status").asText()) {
          case "queued" -> Status.QUEUED;
          case "running" -> Status.RUNNING;
          case "succeeded" -> Status.COMPLETED;
          case "failed" -> Status.FAILED;
          default -> throw new ApiException(502, "INVALID_SAM3D_RESPONSE");
        };
    return new Sam3dJob(id, status);
  }

  private JsonNode json(byte[] body) {
    try {
      return StrictJson.parse(body);
    } catch (ApiException error) {
      if (error.status() != 400) throw error;
      throw new ApiException(502, "INVALID_SAM3D_RESPONSE");
    }
  }

  private HttpRequest.Builder request(String path) {
    return HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(30));
  }

  private byte[] get(String path, int maximum) {
    return send(request(path).GET().build(), maximum, 200);
  }

  private byte[] send(HttpRequest request, int maximum, int expectedStatus) {
    try {
      var response = client.send(request, info -> new LimitedBody(maximum));
      int status = response.statusCode();
      if (status == 404 && request.method().equals("GET"))
        throw new ApiException(404, "SAM3D_JOB_NOT_FOUND");
      if (request.method().equals("POST") && (status == 400 || status == 413 || status == 422))
        throw new ApiException(422, "SAM3D_GENERATION_REJECTED");
      if (status != expectedStatus) throw new ApiException(502, "SAM3D_UPSTREAM_ERROR");
      return response.body();
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new ApiException(503, "SAM3D_UNAVAILABLE");
    } catch (java.io.IOException error) {
      throw new ApiException(503, "SAM3D_UNAVAILABLE");
    }
  }
}
