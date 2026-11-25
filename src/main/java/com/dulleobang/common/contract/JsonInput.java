package com.dulleobang.common.contract;

import com.dulleobang.common.exception.ApiException;
import com.dulleobang.common.json.StrictJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class JsonInput {
  private final ContractValidator contracts;

  public JsonInput(ContractValidator contracts) {
    this.contracts = contracts;
  }

  public JsonNode read(byte[] body, String contentType, String schema) {
    return read(body, contentType, schema, false);
  }

  public JsonNode readMobile(byte[] body, String contentType, String schema) {
    return read(body, contentType, schema, true);
  }

  private JsonNode read(byte[] body, String contentType, String schema, boolean mobile) {
    if (body == null || body.length == 0) throw new ApiException(422, "BODY_REQUIRED");
    if (body.length > 10 * 1024 * 1024) throw new ApiException(413, "BODY_TOO_LARGE");
    if (contentType == null
        || !contentType.split(";", 2)[0].trim().equalsIgnoreCase("application/json"))
      throw new ApiException(415, "UNSUPPORTED_MEDIA_TYPE");
    var node = StrictJson.parse(body);
    if (node instanceof ObjectNode object) {
      for (String field :
          new String[] {"displayName", "name", "title", "fullName", "officeName", "location"})
        if (object.path(field).isTextual()) object.put(field, object.path(field).asText().strip());
      if (object.path("email").isTextual())
        object.put("email", object.path("email").asText().strip().toLowerCase(Locale.ROOT));
    }
    if (mobile) contracts.validateMobile(schema, StrictJson.stringify(node));
    else contracts.validate(schema, StrictJson.stringify(node));
    return node;
  }
}
