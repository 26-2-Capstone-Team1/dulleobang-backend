package com.dulleobang.capture.validation;

import com.dulleobang.common.contract.ContractValidator;
import com.dulleobang.common.exception.ApiException;
import com.dulleobang.common.json.StrictJson;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class CaptureJsonValidator {
  private final ContractValidator contracts;

  public CaptureJsonValidator(ContractValidator contracts) {
    this.contracts = contracts;
  }

  public JsonNode read(byte[] raw, String role) {
    JsonNode value;
    try {
      value = StrictJson.parse(raw);
    } catch (ApiException ex) {
      throw new ApiException(422, "INVALID_CAPTURE_JSON");
    }
    require(value.isObject(), "INVALID_CAPTURE_JSON");
    if (role.equals("CAMERA_MANIFEST"))
      contracts.validate("CameraManifest", StrictJson.stringify(value));
    if (role.equals("ROOMPLAN"))
      for (String key : List.of("walls", "doors", "windows", "objects"))
        require(value.path(key).isArray(), "INVALID_ROOMPLAN_JSON");
    if (role.equals("ROOMPLAN_STRUCTURE")) structure(value);
    return value;
  }

  private void structure(JsonNode value) {
    contracts.validate("RoomPlanStructure", StrictJson.stringify(value));
    for (String key : List.of("surfaces", "objects")) {
      var ids = new HashSet<String>();
      for (var item : value.path(key)) {
        require(ids.add(item.path("id").asText()), "DUPLICATE_ID");
        RigidTransformValidator.quaternion(item.path("transform").path("rotation"));
      }
    }
  }

  private void require(boolean valid, String code) {
    if (!valid) throw new ApiException(422, code);
  }
}
