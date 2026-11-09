package com.dulleobang.job.validation;

import com.dulleobang.common.exception.ApiException;
import com.dulleobang.upload.domain.AssetFile;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class ArtifactPolicy {
  public void validate(String type, JsonNode files) {
    Set<String> expected =
        type.equals("FURNITURE_RECONSTRUCTION")
            ? Set.of("MODEL", "COLLISION")
            : Set.of("SCENE", "GEOMETRY", "COLLISION");
    Set<String> seen = new HashSet<>(), clients = new HashSet<>();
    for (var info : files) {
      String role = info.path("role").asText();
      boolean source = role.equals("SOURCE_MESH") && !type.equals("FURNITURE_RECONSTRUCTION");
      if ((!source && !seen.add(role)) || !clients.add(info.path("clientFileId").asText()))
        throw new ApiException(422, "DUPLICATE_ID");
      String mime =
          role.equals("SCENE")
              ? "application/octet-stream"
              : role.equals("GEOMETRY") ? "application/json" : "model/gltf-binary";
      String format =
          role.equals("SCENE")
              ? (info.path("format").asText().equals("POINT_CLOUD_PLY_V1")
                  ? "POINT_CLOUD_PLY_V1"
                  : "DULLEOBANG_GS_PLY_V1")
              : role.equals("GEOMETRY") ? "ROOM_GEOMETRY_V1" : "GLB_V2";
      long limit =
          (role.equals("GEOMETRY") ? 10L : role.equals("COLLISION") ? 64L : 256L) * 1024 * 1024;
      if ((!source && !expected.contains(role))
          || info.path("byteSize").asLong() > limit
          || !info.path("contentType").asText().equals(mime)
          || !info.path("format").asText().equals(format))
        throw new ApiException(422, "INVALID_ARTIFACT");
    }
    if (!seen.equals(expected)) throw new ApiException(422, "INVALID_ARTIFACT_ROLES");
    long total = 0;
    for (var info : files) total += info.path("byteSize").asLong();
    if (total > 1024L * 1024 * 1024) throw new ApiException(413, "BODY_TOO_LARGE");
  }

  public void match(AssetFile file, JsonNode info) {
    if (!file.clientId().toString().equals(info.path("clientFileId").asText())
        || !file.role().equals(info.path("role").asText())
        || !file.contentType().equals(info.path("contentType").asText())
        || !file.format().equals(info.path("format").asText())
        || file.byteSize() != info.path("byteSize").asLong()
        || !file.sha256().equals(info.path("sha256").asText()))
      throw new ApiException(409, "ARTIFACT_CONFLICT");
  }
}
