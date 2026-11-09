package com.dulleobang.common.contract;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class ApiQueryInterceptor implements HandlerInterceptor {
  private final ApiInventory inventory;
  private final QueryInput parameters;

  public ApiQueryInterceptor(ApiInventory inventory, QueryInput parameters) {
    this.inventory = inventory;
    this.parameters = parameters;
  }

  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    var route = inventory.find(request.getMethod(), request.getRequestURI());
    if (route == null
        && request.getRequestURI().startsWith("/api/")
        && inventory.hasPath(request.getRequestURI()))
      throw new com.dulleobang.common.exception.ApiException(405, "METHOD_NOT_ALLOWED");
    // This adapter validates its mobile aliases after converting price units.
    boolean mobileListingSearch =
        route != null && route.method().equals("GET") && route.path().equals("/api/listings");
    if (route != null && route.path().startsWith("/api/") && !mobileListingSearch) {
      var raw = new LinkedHashMap<String, List<String>>();
      String query = request.getQueryString();
      if (query != null && !query.isEmpty()) {
        try {
          for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            String key =
                java.net.URLDecoder.decode(parts[0], java.nio.charset.StandardCharsets.UTF_8);
            String value =
                parts.length == 1
                    ? ""
                    : java.net.URLDecoder.decode(parts[1], java.nio.charset.StandardCharsets.UTF_8);
            raw.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value);
          }
        } catch (IllegalArgumentException ex) {
          throw new com.dulleobang.common.exception.ApiException(400, "INVALID_QUERY_PARAMETER");
        }
      }
      parameters.read(route.operationId(), raw);
    }
    return true;
  }
}
