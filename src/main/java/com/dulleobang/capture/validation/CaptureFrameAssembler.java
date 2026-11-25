package com.dulleobang.capture.validation;

import static com.dulleobang.capture.validation.CaptureCoordinates.*;

import com.dulleobang.capture.domain.*;
import com.dulleobang.common.exception.ApiException;
import com.dulleobang.common.json.*;
import com.dulleobang.infrastructure.capture.CaptureArchiveContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.springframework.stereotype.Component;

@Component
public class CaptureFrameAssembler {
  public void populate(
      CaptureArchiveContext archive,
      ObjectNode manifest,
      JsonNode meta,
      JsonNode poses,
      double[] world,
      boolean room)
      throws java.io.IOException {
    Set<Long> ids = new HashSet<>();
    for (var pose : poses) require(ids.add(integer(pose.path("frameId"))), "DUPLICATE_ID");
    String captureId = meta.path("captureId").asText();
    var frames = manifest.putArray("frames");
    Map<String, Map<Long, String>> names = frameNames(archive.entries().keySet());
    require(names.get("frames").keySet().equals(ids), "MANIFEST_IMAGE_MISMATCH");
    var depth = names.get("depth").keySet();
    var confidence = names.get("confidence").keySet();
    require(
        (depth.isEmpty() || depth.equals(ids))
            && (confidence.isEmpty() || confidence.equals(ids))
            && (confidence.isEmpty() || !depth.isEmpty()),
        "INVALID_DEPTH_REFERENCE");
    if (!depth.isEmpty()) depth(archive, meta, poses, ids, captureId);
    double previous = -Double.MAX_VALUE, firstTs = number(poses.get(0).path("ts"));
    for (var pose : poses) {
      long id = integer(pose.path("frameId"));
      double ts = number(pose.path("ts"));
      require(ts > previous, "INVALID_FRAME_ORDER");
      previous = ts;
      require(pose.path("tracking").asText().equals("normal"), "INVALID_TRACKING_STATE");
      require(
          meta.path("colorSize").isArray()
              && meta.path("colorSize").size() == 2
              && pose.path("width").equals(meta.path("colorSize").get(0))
              && pose.path("height").equals(meta.path("colorSize").get(1)),
          "IMAGE_DIMENSION_MISMATCH");
      var frame = frames.addObject();
      frame.put(
          "imageClientFileId", archive.add(names.get("frames").get(id), "IMAGE", null).toString());
      frame.put("timestampUs", (long) Math.rint((ts - firstTs) * 1000000));
      frame.set("widthPx", pose.get("width"));
      frame.set("heightPx", pose.get("height"));
      frame.set("intrinsics", pose.path("K"));
      frame.set(
          "cameraToWorld",
          StrictJson.parse(StrictJson.stringify(multiply(world, matrix(pose.path("T_wc"))))));
      frame.put("trackingState", "NORMAL");
      if (room && !depth.isEmpty()) {
        frame.put(
            "depthClientFileId", archive.add(names.get("depth").get(id), "DEPTH", null).toString());
        frame.set("depthWidthPx", meta.path("depthSize").get(0));
        frame.set("depthHeightPx", meta.path("depthSize").get(1));
      }
      if (room && !confidence.isEmpty())
        frame.put(
            "confidenceClientFileId",
            archive.add(names.get("confidence").get(id), "CONFIDENCE", null).toString());
    }
  }

  private Map<String, Map<Long, String>> frameNames(Set<String> names) {
    Map<String, Map<Long, String>> result = new HashMap<>();
    for (String folder : List.of("frames", "depth", "confidence"))
      result.put(folder, new HashMap<>());
    for (String name : names) {
      var parts = name.split("/", -1);
      if (parts.length != 2 || !result.containsKey(parts[0])) continue;
      int dot = parts[1].lastIndexOf('.');
      require(dot > 0, "INVALID_CAPTURE_FILE_NAME");
      String stem = parts[1].substring(0, dot),
          suffix = parts[1].substring(dot).toLowerCase(Locale.ROOT);
      require(
          stem.matches("[0-9]+")
              && (parts[0].equals("frames") ? Set.of(".jpg", ".jpeg", ".png") : Set.of(".png"))
                  .contains(suffix),
          "INVALID_CAPTURE_FILE_NAME");
      require(result.get(parts[0]).put(Long.parseLong(stem), name) == null, "DUPLICATE_ID");
    }
    return result;
  }

  private void depth(
      CaptureArchiveContext archive, JsonNode meta, JsonNode poses, Set<Long> ids, String captureId)
      throws java.io.IOException {
    var depth = archive.json("depth.json", true);
    require(
        depth.isObject()
            && depth.path("captureId").asText().equals(captureId)
            && depth.path("frameCount").equals(meta.path("frameCount"))
            && depth.path("depthSize").equals(meta.path("depthSize"))
            && depth.path("colorSize").equals(meta.path("colorSize")),
        "INVALID_DEPTH_REFERENCE");
    require(
        depth.path("frames").isArray() && depth.path("frames").size() == poses.size(),
        "INVALID_DEPTH_REFERENCE");
    var seen = new HashSet<Long>();
    for (var frame : depth.path("frames"))
      require(seen.add(integer(frame.path("frameId"))), "INVALID_DEPTH_REFERENCE");
    require(seen.equals(ids), "INVALID_DEPTH_REFERENCE");
    require(
        meta.path("depthUnit").asText().equals("uint16 PNG, millimeters, 0 = invalid"),
        "UNSUPPORTED_CAPTURE_PROFILE");
    require(
        meta.path("depthSize").isArray() && meta.path("depthSize").size() == 2,
        "INVALID_DEPTH_REFERENCE");
    integer(meta.path("depthSize").get(0));
    integer(meta.path("depthSize").get(1));
  }

  private static void require(boolean condition, String code) {
    if (!condition) throw new ApiException(422, code);
  }
}
