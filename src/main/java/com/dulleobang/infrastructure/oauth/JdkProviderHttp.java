package com.dulleobang.infrastructure.oauth;

import com.dulleobang.common.exception.ApiException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class JdkProviderHttp implements ProviderHttp {
  private final HttpClient client =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(5))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  public Reply get(URI uri, Map<String, String> headers) {
    try {
      var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET();
      headers.forEach(request::header);
      var reply = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
      try (var body = reply.body()) {
        byte[] data = body.readNBytes(1048577);
        if (data.length > 1048576) throw new ApiException(503, "OAUTH_PROVIDER_UNAVAILABLE");
        return new Reply(reply.statusCode(), new String(data, StandardCharsets.UTF_8));
      }
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new ApiException(503, "OAUTH_PROVIDER_UNAVAILABLE");
    } catch (java.io.IOException | IllegalArgumentException ex) {
      throw new ApiException(503, "OAUTH_PROVIDER_UNAVAILABLE");
    }
  }
}
