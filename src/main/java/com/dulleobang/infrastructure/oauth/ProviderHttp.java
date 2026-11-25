package com.dulleobang.infrastructure.oauth;

import java.net.URI;
import java.util.Map;

public interface ProviderHttp {
  record Reply(int status, String body) {}

  Reply get(URI uri, Map<String, String> headers);
}
