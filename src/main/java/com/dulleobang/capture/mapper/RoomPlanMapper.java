package com.dulleobang.capture.mapper;

import static com.dulleobang.capture.validation.CaptureCoordinates.*;

import com.dulleobang.common.json.StrictJson;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class RoomPlanMapper {
  public JsonNode normalize(JsonNode source, JsonNode raw, double[] world) {
    List<Object> surfaces = List.of(), objects = List.of();
    if (source != null) {
      surfaces = RoomStructureMapper.surfaces(source, raw, world);
      objects = RoomStructureMapper.objects(source, world);
    } else if (raw != null) {
      surfaces = RawRoomPlanMapper.surfaces(raw, world);
      objects = RawRoomPlanMapper.objects(raw, world);
    }
    return StrictJson.parse(
        StrictJson.stringify(
            Map.of(
                "schemaVersion",
                "1.0",
                "coordinateSystem",
                "RH_Y_UP_Z_BACK",
                "unit",
                "METER",
                "surfaces",
                surfaces,
                "objects",
                objects)));
  }
}
