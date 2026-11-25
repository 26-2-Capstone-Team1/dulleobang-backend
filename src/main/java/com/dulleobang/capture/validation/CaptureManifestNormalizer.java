package com.dulleobang.capture.validation;

import static com.dulleobang.capture.validation.CaptureCoordinates.*;

import com.dulleobang.capture.domain.*;
import com.dulleobang.capture.mapper.RoomPlanMapper;
import com.dulleobang.common.exception.ApiException;
import com.dulleobang.common.json.*;
import com.dulleobang.infrastructure.capture.CaptureArchiveContext;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.zip.*;
import org.springframework.stereotype.Component;

@Component
public class CaptureManifestNormalizer {
  private final RoomPlanMapper roomPlans;
  private final CaptureFrameAssembler frames;

  public CaptureManifestNormalizer(RoomPlanMapper roomPlans, CaptureFrameAssembler frames) {
    this.roomPlans = roomPlans;
    this.frames = frames;
  }

  public void normalize(CaptureArchiveContext archive, boolean room) throws java.io.IOException {
    var meta = archive.json("meta.json", true);
    var poses = archive.json("poses.json", true);
    profile(meta, poses, room);
    var rawRoom = archive.json("roomplan.json", false);
    var structure = archive.json("roomplan_structure.json", false);
    if (rawRoom != null) {
      require(rawRoom.isObject(), "INVALID_ROOMPLAN_JSON");
      for (String field : List.of("walls", "doors", "windows", "objects"))
        require(rawRoom.path(field).isArray(), "INVALID_ROOMPLAN_JSON");
    }
    var alignment = CaptureAlignment.compute(meta, poses, structure, rawRoom);
    double[] world = alignment.world(), roomWorld = alignment.roomWorld();
    var manifest = document(meta, world, roomWorld);
    frames.populate(archive, manifest, meta, poses, world, room);
    archive.add(
        "camera.json",
        "CAMERA_MANIFEST",
        StrictJson.stringify(manifest).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    if (room && rawRoom != null)
      archive.add("roomplan.json", "ROOMPLAN", archive.bytes("roomplan.json", 10L * 1024 * 1024));
    if (room && (structure != null || rawRoom != null))
      archive.add(
          "roomplan_structure.json",
          "ROOMPLAN_STRUCTURE",
          StrictJson.stringify(roomPlans.normalize(structure, rawRoom, roomWorld))
              .getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private void profile(
      com.fasterxml.jackson.databind.JsonNode meta,
      com.fasterxml.jackson.databind.JsonNode poses,
      boolean room) {
    require(
        meta.isObject()
            && poses.isArray()
            && poses.size() >= (room ? 30 : 12)
            && poses.size() <= (room ? 1000 : 300),
        "INVALID_CAPTURE_METADATA");
    require(
        meta.path("kind").asText().equals(room ? "room" : "furniture")
            && meta.path("arSdk").asText().equals("ARKit")
            && meta.path("coordinate")
                .asText()
                .startsWith("arkit_world; meters; y-up; right-handed")
            && meta.path("imageOrientation")
                .asText()
                .equals("sensor_native_landscape (not rotated; K matches the JPG)"),
        "UNSUPPORTED_CAPTURE_PROFILE");
    require(integer(meta.path("frameCount")) == poses.size(), "INVALID_CAPTURE_METADATA");
    String captureId = meta.path("captureId").asText();
    UUID.fromString(captureId);
  }

  private ObjectNode document(
      com.fasterxml.jackson.databind.JsonNode meta, double[] world, double[] roomWorld) {
    String created =
        ApiTime.format(OffsetDateTime.parse(meta.path("createdAt").asText()).toInstant());
    return (ObjectNode)
        StrictJson.parse(
            StrictJson.stringify(
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
                    created,
                    "captureToWorld",
                    world,
                    "roomPlanToWorld",
                    roomWorld)));
  }

  private static void require(boolean condition, String code) {
    if (!condition) throw new ApiException(422, code);
  }
}
