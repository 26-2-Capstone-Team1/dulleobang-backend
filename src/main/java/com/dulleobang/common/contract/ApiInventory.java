package com.dulleobang.common.contract;

import com.dulleobang.common.json.StrictJson;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class ApiInventory {
  public record Route(
      String method, String path, String audience, String operationId, Pattern pattern) {}

  private final List<Route> routes;
  private final List<Route> extensions;
  private final List<Route> all;

  public ApiInventory() throws Exception {
    routes = load("contract/api-inventory.json");
    extensions =
        java.util.stream.Stream.concat(
                load("contract/extensions/sam3d.json").stream(),
                load("contract/extensions/worker.json").stream())
            .toList();
    all = java.util.stream.Stream.concat(routes.stream(), extensions.stream()).toList();
  }

  private List<Route> load(String file) throws java.io.IOException {
    try (var in = new ClassPathResource(file).getInputStream()) {
      var json = StrictJson.parse(in.readAllBytes());
      var result = new ArrayList<Route>();
      for (var node : json.path("routes")) {
        String path = node.path("path").asText();
        String regex =
            Arrays.stream(path.split("/", -1))
                .map(p -> p.startsWith("{") ? "[^/]+" : Pattern.quote(p))
                .collect(java.util.stream.Collectors.joining("/"));
        result.add(
            new Route(
                node.path("method").asText(),
                path,
                node.path("audience").asText(),
                node.path("operationId").asText(),
                Pattern.compile("^" + regex + "$")));
      }
      return List.copyOf(result);
    }
  }

  public Route find(String method, String path) {
    return all.stream()
        .filter(r -> r.method().equals(method) && r.pattern().matcher(path).matches())
        .findFirst()
        .orElse(null);
  }

  public boolean hasPath(String path) {
    return all.stream().anyMatch(r -> r.pattern().matcher(path).matches());
  }

  public List<Route> routes() {
    return routes;
  }

  public List<Route> extensions() {
    return extensions;
  }

  public List<Route> allRoutes() {
    return all;
  }

  public boolean isPublic(Route route) {
    return List.of("signup", "login", "refreshToken", "logout", "loginKakao", "loginGoogle")
        .contains(route.operationId());
  }
}
