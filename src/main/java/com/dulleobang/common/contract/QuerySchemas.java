package com.dulleobang.common.contract;

import com.dulleobang.common.json.StrictJson;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.*;
import org.springframework.core.io.ClassPathResource;

/** Loads query declarations without coupling request parsing to resource I/O. */
final class QuerySchemas {
  private QuerySchemas() {}

  static Map<String, Map<String, JsonNode>> load() throws IOException {
    var operations = new HashMap<String, Map<String, JsonNode>>();
    loadApi(operations);
    loadExtensions(operations);
    return operations;
  }

  private static void loadApi(Map<String, Map<String, JsonNode>> operations) throws IOException {
    try (var stream = new ClassPathResource("contract/v1/openapi.json").getInputStream()) {
      var spec = StrictJson.parse(stream.readAllBytes());
      for (var path : spec.path("paths"))
        for (var operation : path) {
          var parameters = new LinkedHashMap<String, JsonNode>();
          for (var parameter : operation.path("parameters"))
            if (parameter.path("in").asText().equals("query"))
              parameters.put(parameter.path("name").asText(), parameter.path("schema"));
          operations.put(operation.path("operationId").asText(), parameters);
        }
    }
  }

  private static void loadExtensions(Map<String, Map<String, JsonNode>> operations)
      throws IOException {
    try (var stream = new ClassPathResource("contract/extensions/worker.json").getInputStream()) {
      var queries = StrictJson.parse(stream.readAllBytes()).path("queries");
      queries
          .fields()
          .forEachRemaining(
              operation -> {
                var parameters = new LinkedHashMap<String, JsonNode>();
                operation
                    .getValue()
                    .fields()
                    .forEachRemaining(entry -> parameters.put(entry.getKey(), entry.getValue()));
                operations.put(operation.getKey(), parameters);
              });
    }
  }
}
