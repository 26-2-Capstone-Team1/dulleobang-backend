package com.dulleobang.common.contract;

import com.dulleobang.common.exception.ApiException;
import com.dulleobang.common.json.StrictJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class ContractValidator {
  private final JsonNode spec;
  private final JsonNode mobile;
  private final JsonNode sam3d;

  public ContractValidator() throws Exception {
    try (var in = new ClassPathResource("contract/v1/openapi.json").getInputStream()) {
      spec = StrictJson.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
      Pr2Extensions.apply(spec);
    }
    try (var in = new ClassPathResource("contract/mobile-openapi.json").getInputStream()) {
      mobile = StrictJson.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
      Pr2Extensions.apply(mobile);
    }
    try (var in = new ClassPathResource("contract/extensions/sam3d.json").getInputStream()) {
      sam3d = StrictJson.parse(in.readAllBytes());
    }
  }

  public void validate(String name, String body) {
    validateFrom(name.startsWith("Sam3d") ? sam3d : spec, name, body);
  }

  public void validateMobile(String name, String body) {
    validateFrom(mobile, name, body);
  }

  private void validateFrom(JsonNode document, String name, String body) {
    JsonNode schema = document.path("components").path("schemas").get(name);
    if (schema == null) throw new IllegalArgumentException("Unknown schema: " + name);
    ObjectNode root = schema.deepCopy();
    root.set("components", document.path("components"));
    var config = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
    var validator =
        JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(root, config);
    if (!validator.validate(StrictJson.parse(body)).isEmpty())
      throw new ApiException(422, "VALIDATION_FAILED");
  }
}
