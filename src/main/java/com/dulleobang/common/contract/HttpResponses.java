package com.dulleobang.common.contract;

import com.dulleobang.common.idempotency.StoredResponse;
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
    response.headers().forEach((key, value) -> builder.header(key, value));
    return builder.body(response.body());
  }
}
