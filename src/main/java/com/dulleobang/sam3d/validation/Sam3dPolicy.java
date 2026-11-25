package com.dulleobang.sam3d.validation;

import com.dulleobang.common.exception.ApiException;
import com.dulleobang.common.json.StrictJson;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class Sam3dPolicy {
  public JsonNode dimensions(JsonNode job, JsonNode generation) {
    require(
        generation.path("inference_verified").isBoolean()
            && generation.path("inference_verified").asBoolean());
    require(generation.path("engine").asText().equals("SAM 3D Objects"));
    require(generation.path("coordinate_system").asText().equals("metres; Y-up; bottom at y=0"));
    var dims = generation.path("dimensions");
    require(dims.isObject());
    for (String key : List.of("width", "height", "depth")) {
      var value = dims.path(key);
      require(
          value.isNumber()
              && Double.isFinite(value.asDouble())
              && value.asDouble() >= .02
              && value.asDouble() <= 20);
      require(
          job.path("dimensions").path(key).isNumber()
              && Math.abs(value.asDouble() - job.path("dimensions").path(key).asDouble()) < 1e-8);
    }
    return StrictJson.parse(
        StrictJson.stringify(
            Map.of(
                "width",
                dims.path("width").asDouble(),
                "height",
                dims.path("height").asDouble(),
                "depth",
                dims.path("depth").asDouble())));
  }

  public JsonNode bounds(JsonNode dims) {
    double w = dims.path("width").asDouble(),
        h = dims.path("height").asDouble(),
        d = dims.path("depth").asDouble();
    return StrictJson.parse(
        StrictJson.stringify(
            Map.of(
                "min",
                Map.of("x", -w / 2, "y", 0, "z", -d / 2),
                "max",
                Map.of("x", w / 2, "y", h, "z", d / 2))));
  }

  private void require(boolean valid) {
    if (!valid) throw new ApiException(422, "INVALID_SAM3D_RESULT");
  }
}
