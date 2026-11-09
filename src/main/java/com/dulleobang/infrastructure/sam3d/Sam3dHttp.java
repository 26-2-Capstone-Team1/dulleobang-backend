package com.dulleobang.infrastructure.sam3d;

import com.dulleobang.common.exception.ApiException;
import com.dulleobang.common.json.StrictJson;
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

  public Sam3dModel fetch(String id) {
    if (!enabled) throw new ApiException(503, "SAM3D_NOT_CONFIGURED");
    if (!id.matches("[0-9a-f]{32}")) throw new ApiException(422, "VALIDATION_FAILED");
    String path = "/api/jobs/" + id;
    var job = json(path, 65536);
    if (!job.path("id").asText().equals(id)) throw new ApiException(502, "INVALID_SAM3D_RESPONSE");
    switch (job.path("status").asText()) {
      case "queued", "running" -> throw new ApiException(409, "SAM3D_JOB_NOT_READY");
      case "failed" -> throw new ApiException(422, "SAM3D_JOB_FAILED");
      case "succeeded" -> {}
      default -> throw new ApiException(502, "INVALID_SAM3D_RESPONSE");
    }
    var dims = policy.dimensions(job, json(path + "/generation.json", 1048576));
    return new Sam3dModel(get(path + "/model.glb", 64 * 1024 * 1024), dims, policy.bounds(dims));
  }

  private JsonNode json(String path, int maximum) {
    try {
      return StrictJson.parse(get(path, maximum));
    } catch (ApiException error) {
      if (error.status() != 400) throw error;
      throw new ApiException(502, "INVALID_SAM3D_RESPONSE");
    }
  }

  private byte[] get(String path, int maximum) {
    try {
      var request =
          HttpRequest.newBuilder(URI.create(base + path))
              .timeout(Duration.ofSeconds(30))
              .GET()
              .build();
      var response = client.send(request, info -> new LimitedBody(maximum));
      if (response.statusCode() == 404) throw new ApiException(404, "SAM3D_JOB_NOT_FOUND");
      if (response.statusCode() != 200) throw new ApiException(502, "SAM3D_UPSTREAM_ERROR");
      return response.body();
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new ApiException(503, "SAM3D_UNAVAILABLE");
    } catch (java.io.IOException error) {
      throw new ApiException(503, "SAM3D_UNAVAILABLE");
    }
  }
}
