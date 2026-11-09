package com.dulleobang.placement.validation;

import com.dulleobang.common.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class PlacementInputPolicy {
  public void validate(JsonNode input, Instant now) {
    var identifiers = new HashSet<String>();
    var furniture = new HashSet<String>();
    for (var item : input.path("placements")) {
      String placementId = UUID.fromString(item.path("placementId").asText()).toString();
      String furnitureId = UUID.fromString(item.path("furnitureId").asText()).toString();
      ((ObjectNode) item).put("placementId", placementId).put("furnitureId", furnitureId);
      if (!identifiers.add(placementId) || !furniture.add(furnitureId))
        throw new ApiException(422, "DUPLICATE_ID");
      normalize((ObjectNode) item.path("transform").path("rotation"));
    }
    collisionReport(input.path("clientCollisionReport"), identifiers, now);
  }

  private void normalize(ObjectNode q) {
    double norm = 0;
    for (String axis : List.of("x", "y", "z", "w")) norm += Math.pow(q.path(axis).asDouble(), 2);
    norm = Math.sqrt(norm);
    if (!Double.isFinite(norm) || Math.abs(norm - 1) > 1e-4)
      throw new ApiException(422, "INVALID_ROTATION");
    double first = 0;
    for (String axis : List.of("x", "y", "z"))
      if (q.path(axis).asDouble() != 0) {
        first = q.path(axis).asDouble();
        break;
      }
    int sign = q.path("w").asDouble() < 0 || (q.path("w").asDouble() == 0 && first < 0) ? -1 : 1;
    for (String axis : List.of("x", "y", "z", "w")) {
      double value = q.path(axis).asDouble() / norm * sign;
      q.put(axis, value == 0 ? 0 : value);
    }
  }

  private void collisionReport(JsonNode report, Set<String> ids, Instant now) {
    if (report.isNull()) return;
    boolean valid = report.path("isPlaceable").asBoolean() == report.path("collisions").isEmpty();
    for (var collision : report.path("collisions"))
      valid &= ids.contains(UUID.fromString(collision.path("placementId").asText()).toString());
    try {
      valid &=
          !OffsetDateTime.parse(report.path("evaluatedAt").asText())
              .toInstant()
              .isAfter(now.plusSeconds(60));
    } catch (DateTimeException ex) {
      valid = false;
    }
    if (!valid) throw new ApiException(422, "INVALID_COLLISION_REPORT");
  }
}
