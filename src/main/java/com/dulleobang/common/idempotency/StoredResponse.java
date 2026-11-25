package com.dulleobang.common.idempotency;

import java.util.Map;

public record StoredResponse(int status, String body, Map<String, String> headers) {}
