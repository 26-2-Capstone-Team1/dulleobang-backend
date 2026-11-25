package com.dulleobang.capture.validation;

import com.dulleobang.common.exception.ApiException;
import com.dulleobang.upload.domain.AssetFile;
import com.dulleobang.upload.validation.ImageValidator;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class CaptureManifestValidator {
  public void validate(
      JsonNode manifest, List<AssetFile> files, Map<String, ImageValidator.Size> sizes) {
    Map<String, AssetFile> byClient = new HashMap<>();
    Set<String> expected = new HashSet<>(), seen = new HashSet<>(), used = new HashSet<>();
    for (var file : files) {
      byClient.put(file.clientId().toString(), file);
      if (file.role().equals("IMAGE")) expected.add(file.clientId().toString());
    }
    long previous = -1;
    for (var frame : manifest.path("frames")) {
      String image = frame.path("imageClientFileId").asText();
      require(seen.add(image), "DUPLICATE_ID");
      require(expected.contains(image), "MANIFEST_IMAGE_MISMATCH");
      long timestamp = frame.path("timestampUs").asLong();
      require(timestamp > previous, "INVALID_FRAME_ORDER");
      previous = timestamp;
      require(
          new ImageValidator.Size(frame.path("widthPx").asInt(), frame.path("heightPx").asInt())
              .equals(sizes.get(image)),
          "IMAGE_DIMENSION_MISMATCH");
      var k = frame.path("intrinsics");
      require(
          k.path("cx").asDouble() < frame.path("widthPx").asInt()
              && k.path("cy").asDouble() < frame.path("heightPx").asInt(),
          "INVALID_INTRINSICS");
      RigidTransformValidator.matrix(frame.path("cameraToWorld"));
      int fields = 0;
      for (String key : List.of("depthClientFileId", "depthWidthPx", "depthHeightPx"))
        if (frame.has(key)) fields++;
      require(fields == 0 || fields == 3, "INVALID_DEPTH_REFERENCE");
      require(!frame.has("confidenceClientFileId") || fields == 3, "INVALID_CONFIDENCE_REFERENCE");
      if (fields == 3)
        for (String role : List.of("DEPTH", "CONFIDENCE")) {
          String key = role.equals("DEPTH") ? "depthClientFileId" : "confidenceClientFileId";
          if (!frame.has(key)) continue;
          String id = frame.path(key).asText();
          require(
              used.add(id)
                  && byClient.containsKey(id)
                  && byClient.get(id).role().equals(role)
                  && new ImageValidator.Size(
                          frame.path("depthWidthPx").asInt(), frame.path("depthHeightPx").asInt())
                      .equals(sizes.get(id)),
              "INVALID_DEPTH_REFERENCE");
        }
    }
    require(seen.equals(expected), "MANIFEST_IMAGE_MISMATCH");
    Set<String> depth = new HashSet<>();
    for (var file : files)
      if (Set.of("DEPTH", "CONFIDENCE").contains(file.role()))
        depth.add(file.clientId().toString());
    require(used.equals(depth), "UNREFERENCED_DEPTH_FILE");
    for (String key : List.of("captureToWorld", "roomPlanToWorld"))
      if (manifest.has(key)) RigidTransformValidator.matrix(manifest.path(key));
  }

  private void require(boolean condition, String code) {
    if (!condition) throw new ApiException(422, code);
  }
}
