package com.dulleobang.upload.validation;

import com.dulleobang.common.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class UploadFileClassifier {
  public List<JsonNode> classify(JsonNode input) {
    String purpose = input.path("purpose").asText();
    boolean room = purpose.equals("ROOM_CAPTURE"),
        capture = room || purpose.equals("FURNITURE_CAPTURE");
    String target =
        purpose.equals("LISTING_AGENT_DOCUMENT")
            ? "USER"
            : purpose.equals("FURNITURE_CAPTURE") ? "FURNITURE" : "LISTING";
    require(target.equals(input.path("targetType").asText()), "INVALID_UPLOAD_PURPOSE");
    var files = new ArrayList<JsonNode>();
    var ids = new HashSet<String>();
    long total = 0;
    for (var original : input.path("files")) {
      require(ids.add(original.path("clientFileId").asText()), "DUPLICATE_ID");
      var f = file(original, purpose, room, capture);
      total += f.path("byteSize").asLong();
      files.add(f);
    }
    counts(files, purpose, room, capture);
    require(total <= 5L * 1024 * 1024 * 1024, "UPLOAD_LIMIT_EXCEEDED");
    return List.copyOf(files);
  }

  private JsonNode file(JsonNode original, String purpose, boolean room, boolean capture) {
    var f = (ObjectNode) original.deepCopy();
    String mime = f.path("contentType").asText();
    String role =
        f.path("role")
            .asText(
                capture
                    ? (mime.equals("application/json") ? "CAMERA_MANIFEST" : "IMAGE")
                    : purpose.equals("LISTING_PHOTO") ? "PHOTO" : "LISTING_AGENT_DOCUMENT");
    Set<String> allowed =
        room
            ? Set.of(
                "IMAGE", "CAMERA_MANIFEST", "DEPTH", "CONFIDENCE", "ROOMPLAN", "ROOMPLAN_STRUCTURE")
            : capture
                ? Set.of("IMAGE", "CAMERA_MANIFEST")
                : Set.of(purpose.equals("LISTING_PHOTO") ? "PHOTO" : "LISTING_AGENT_DOCUMENT");
    require(allowed.contains(role), "INVALID_UPLOAD_ROLE");
    require(
        (Set.of("CAMERA_MANIFEST", "ROOMPLAN", "ROOMPLAN_STRUCTURE").contains(role)
            ? mime.equals("application/json")
            : Set.of("DEPTH", "CONFIDENCE").contains(role)
                ? mime.equals("image/png")
                : Set.of("image/png", "image/jpeg").contains(mime)),
        "INVALID_UPLOAD_ROLE");
    require(
        f.path("byteSize").asLong() <= (mime.equals("application/json") ? 10L : 20L) * 1024 * 1024,
        "UPLOAD_LIMIT_EXCEEDED");
    f.put("role", role);
    return f;
  }

  private void counts(List<JsonNode> files, String purpose, boolean room, boolean capture) {
    if (capture) {
      long images = count(files, "IMAGE");
      require(
          images >= (room ? 30 : 12)
              && images <= (room ? 1000 : 300)
              && count(files, "CAMERA_MANIFEST") == 1,
          "INVALID_CAPTURE_FILES");
      for (String role : List.of("DEPTH", "CONFIDENCE"))
        require(count(files, role) == 0 || count(files, role) == images, "INVALID_CAPTURE_FILES");
      require(
          count(files, "CONFIDENCE") == 0 || count(files, "DEPTH") > 0, "INVALID_CAPTURE_FILES");
      require(
          count(files, "ROOMPLAN") <= 1 && count(files, "ROOMPLAN_STRUCTURE") <= 1,
          "INVALID_CAPTURE_FILES");
    } else
      require(
          files.size() >= 1 && files.size() <= (purpose.equals("LISTING_PHOTO") ? 30 : 1),
          "INVALID_CAPTURE_FILES");
  }

  private long count(List<JsonNode> files, String role) {
    return files.stream().filter(f -> f.path("role").asText().equals(role)).count();
  }

  private void require(boolean condition, String code) {
    if (!condition) throw new ApiException(422, code);
  }

  public String format(JsonNode file) {
    return switch (file.path("role").asText()) {
      case "CAMERA_MANIFEST" -> "CAMERA_JSON_V1";
      case "DEPTH" -> "DEPTH_PNG_U16_MM_V1";
      case "CONFIDENCE" -> "CONFIDENCE_PNG_U8_V1";
      case "ROOMPLAN" -> "ROOMPLAN_JSON";
      case "ROOMPLAN_STRUCTURE" -> "ROOMPLAN_STRUCTURE_V1";
      default -> file.path("contentType").asText().equals("image/png") ? "PNG" : "JPEG";
    };
  }
}
