package com.dulleobang.common.contract;

import com.dulleobang.common.idempotency.StoredResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

public final class HttpResponses {
  private HttpResponses() {}

  public static ResponseEntity<String> json(Object value) {
    return json(value, null);
  }

  public static ResponseEntity<String> json(Object value, Number version) {
    var builder = ResponseEntity.ok().header("Content-Type", "application/json");
    if (version != null) builder.eTag("\"" + version + "\"");
    return builder.body(com.dulleobang.common.json.StrictJson.stringify(value));
  }

  public static ResponseEntity<String> stored(StoredResponse response) {
    var builder =
        ResponseEntity.status(response.status()).header("Content-Type", "application/json");
    applyStoredHeaders(builder, response);
    return builder.body(response.body());
  }

  public static void applyStoredHeaders(
      ResponseEntity.BodyBuilder builder, StoredResponse response) {
    response
        .headers()
        .forEach(
            (name, value) ->
                builder.header(
                    name,
                    HttpHeaders.LOCATION.equalsIgnoreCase(name) ? publicLocation(value) : value));
  }

  private static String publicLocation(String value) {
    if (value.equals("/api/v1/users/me/listing-agent-verification"))
      return "/api/users/me/agent-profile";
    if (value.startsWith("/api/v1/chats/"))
      return "/api/chat/" + value.substring("/api/v1/chats/".length());
    if (value.startsWith("/api/v1/") && !value.startsWith("/api/v1/internal/"))
      return "/api/" + value.substring("/api/v1/".length());
    return value;
  }
}
