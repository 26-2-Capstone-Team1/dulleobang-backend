package com.dulleobang.common.contract;

import com.dulleobang.common.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class QueryInput {
  private final Map<String, Map<String, JsonNode>> operations;

  public QueryInput() throws java.io.IOException {
    operations = QuerySchemas.load();
  }

  public Map<String, Object> read(String operation, Map<String, List<String>> raw) {
    var declared = operations.getOrDefault(operation, Map.of());
    if (!declared.keySet().containsAll(raw.keySet()))
      throw new ApiException(400, "UNKNOWN_QUERY_PARAMETER");
    var output = new LinkedHashMap<String, Object>();
    for (var entry : declared.entrySet()) {
      String name = entry.getKey();
      var schema = entry.getValue();
      var values = raw.get(name);
      if (values == null) {
        if (schema.has("default")) output.put(name, value(schema.get("default")));
        continue;
      }
      output.put(name, checkedValue(schema, values));
    }
    return output;
  }

  private Object checkedValue(JsonNode schema, List<String> values) {
    try {
      Object parsed = QueryValueParser.parse(schema.path("type").asText(), values);
      QuerySchemaValidator.validate(schema, parsed);
      return parsed;
    } catch (ApiException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new ApiException(400, "INVALID_QUERY_PARAMETER");
    }
  }

  private Object value(JsonNode node) {
    if (node.isIntegralNumber()) return node.longValue();
    if (node.isBoolean()) return node.booleanValue();
    return node.asText();
  }

  public static int limit(Map<String, Object> q) {
    return ((Number) q.getOrDefault("limit", 20)).intValue();
  }
}
