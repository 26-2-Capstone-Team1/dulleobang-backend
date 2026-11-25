package com.dulleobang.verification.service;

import com.dulleobang.common.exception.ApiException;
import com.dulleobang.common.idempotency.*;
import com.dulleobang.common.idempotency.service.IdempotencyService;
import com.dulleobang.common.json.StrictJson;
import com.dulleobang.upload.dto.UploadedFile;
import com.dulleobang.upload.service.MultipartStoreService;
import com.dulleobang.user.service.UserService;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class MobileAgentApplicationService {
  private final AgentVerificationService agents;
  private final MultipartStoreService store;
  private final UserService users;
  private final IdempotencyService idempotency;

  public MobileAgentApplicationService(
      AgentVerificationService agents,
      MultipartStoreService store,
      UserService users,
      IdempotencyService idempotency) {
    this.agents = agents;
    this.store = store;
    this.users = users;
    this.idempotency = idempotency;
  }

  public StoredResponse apply(
      UUID actor, String path, String key, List<UploadedFile> files, Map<String, String> fields) {
    var values = new LinkedHashMap<String, Object>();
    for (var entry : Map.of("name", 80, "licenseNumber", 80, "officeName", 150).entrySet()) {
      String value = fields.getOrDefault(entry.getKey(), "").strip();
      if (value.isEmpty() || value.length() > entry.getValue())
        throw new ApiException(422, "VALIDATION_FAILED");
      values.put(entry.getKey(), value);
    }
    if (fields.getOrDefault("documentName", "").length() > 255)
      throw new ApiException(422, "VALIDATION_FAILED");
    return idempotency.execute(
        actor.toString(),
        "POST",
        path,
        key,
        StrictJson.stringify(Map.of("fields", fields, "files", store.fingerprint(files))),
        () -> {
          var uploaded = store.store(actor, "LISTING_AGENT_DOCUMENT", "USER", actor, files);
          values.put("documentFileId", uploaded.get(0));
          values.put("phone", users.get(actor).phone());
          agents.apply(
              actor,
              StrictJson.parse(StrictJson.stringify(values)),
              fields.getOrDefault("documentName", files.get(0).fileName()));
          return new StoredResponse(
              201,
              StrictJson.stringify(agents.get(actor, true)),
              Map.of("Location", "/api/v1/users/me/listing-agent-verification"));
        });
  }
}
