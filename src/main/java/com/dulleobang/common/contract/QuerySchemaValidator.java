package com.dulleobang.common.contract;

import com.dulleobang.common.json.StrictJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.*;

final class QuerySchemaValidator {
  private QuerySchemaValidator() {}

  static void validate(JsonNode schema, Object value) {
    var config = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
    var validator =
        JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schema, config);
    if (!validator.validate(StrictJson.parse(StrictJson.stringify(value))).isEmpty())
      throw new IllegalArgumentException();
  }
}
