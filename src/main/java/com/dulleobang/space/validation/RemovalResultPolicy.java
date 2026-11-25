package com.dulleobang.space.validation;

import com.dulleobang.common.exception.ApiException;
import com.dulleobang.common.json.StrictJson;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class RemovalResultPolicy {
  public void validate(
      UUID state, JsonNode result, JsonNode source, List<String> removedIds, Set<String> nodes) {
    require(
        state.toString().equalsIgnoreCase(result.path("roomStateId").asText()),
        "REMOVAL_TARGET_MISMATCH");
    var submitted = new HashSet<String>();
    for (var id : result.path("removedObjectIds"))
      require(submitted.add(UUID.fromString(id.asText()).toString()), "DUPLICATE_ID");
    var removed = new HashSet<>(removedIds);
    require(removed.equals(submitted), "REMOVAL_TARGET_MISMATCH");
    var remaining = new TreeMap<String, JsonNode>();
    for (var object : source.path("objects"))
      if (removed.contains(object.path("objectId").asText()))
        require(!nodes.contains(object.path("collisionNode").asText()), "REMOVED_COLLIDER_PRESENT");
      else remaining.put(object.path("objectId").asText(), object);
    var bundle = result.path("bundle");
    var actual = new TreeMap<String, JsonNode>();
    bundle.path("objects").forEach(o -> actual.put(o.path("objectId").asText(), o));
    require(equal(remaining, actual), "REMOVAL_OBJECT_MISMATCH");
    MeshRemovalPolicy.validate(source, bundle, removed);
    require(
        equal(source.path("geometry"), bundle.path("geometry"))
            && equal(source.path("roomDimensions"), bundle.path("roomDimensions")),
        "REMOVAL_GEOMETRY_MISMATCH");
    boolean warning = false;
    for (var w : bundle.path("quality").path("warnings"))
      warning |= w.asText().equals("INPAINTED_REGION");
    require(warning, "REMOVAL_WARNING_REQUIRED");
  }

  private boolean equal(Object a, Object b) {
    return StrictJson.canonical(StrictJson.stringify(a))
        .equals(StrictJson.canonical(StrictJson.stringify(b)));
  }

  private void require(boolean condition, String code) {
    if (!condition) throw new ApiException(422, code);
  }
}
