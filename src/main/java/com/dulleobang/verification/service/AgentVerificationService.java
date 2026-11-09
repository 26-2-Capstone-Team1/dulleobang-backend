package com.dulleobang.verification.service;

import com.dulleobang.common.exception.ApiException;
import com.dulleobang.common.idempotency.*;
import com.dulleobang.common.idempotency.service.IdempotencyService;
import com.dulleobang.common.json.*;
import com.dulleobang.upload.repository.UploadRepository;
import com.dulleobang.verification.domain.AgentApplication;
import com.dulleobang.verification.repository.AgentVerificationRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class AgentVerificationService {
  private final AgentVerificationRepository repository;
  private final UploadRepository uploads;
  private final IdempotencyService idempotency;
  private final Clock clock;

  public AgentVerificationService(
      AgentVerificationRepository repository,
      UploadRepository uploads,
      IdempotencyService idempotency,
      Clock clock) {
    this.repository = repository;
    this.uploads = uploads;
    this.idempotency = idempotency;
    this.clock = clock;
  }

  public Map<String, Object> get(UUID actor, boolean mobile) {
    var application = repository.current(actor);
    if (mobile && application.isEmpty()) throw new ApiException(404, "RESOURCE_NOT_FOUND");
    var result = new LinkedHashMap<String, Object>();
    var a = application.orElse(null);
    result.put("status", a == null ? "NONE" : a.status());
    result.put("name", a == null ? null : a.name());
    result.put("licenseNumber", a == null ? null : a.licenseNumber());
    result.put("officeName", a == null ? null : a.officeName());
    result.put("phone", a == null ? null : a.phone());
    result.put("documentFileId", a == null ? null : a.document());
    result.put("requestedAt", a == null ? null : ApiTime.format(a.requestedAt()));
    result.put(
        "reviewedAt", a == null || a.reviewedAt() == null ? null : ApiTime.format(a.reviewedAt()));
    result.put("rejectReason", a == null ? null : a.rejectReason());
    if (mobile) {
      result.put("documentName", a == null ? null : a.documentName());
      result.put("verifiedAt", result.get("reviewedAt"));
    }
    return result;
  }

  public void apply(UUID actor, JsonNode input, String documentName) {
    repository.lockUser(actor);
    if (repository.current(actor).map(a -> a.status().equals("PENDING")).orElse(false))
      throw new ApiException(409, "VERIFICATION_PENDING");
    var file =
        uploads
            .asset(UUID.fromString(input.path("documentFileId").asText()), false)
            .orElseThrow(() -> new ApiException(404, "RESOURCE_NOT_FOUND"));
    if (!file.owner().equals(actor)) throw new ApiException(404, "RESOURCE_NOT_FOUND");
    if (!file.purpose().equals("LISTING_AGENT_DOCUMENT")
        || !file.targetType().equals("USER")
        || !file.targetId().equals(actor)
        || !file.verified()) throw new ApiException(422, "INVALID_EVIDENCE");
    repository.add(
        new AgentApplication(
            UUID.randomUUID(),
            actor,
            "PENDING",
            input.path("name").asText(),
            input.path("licenseNumber").asText(),
            input.path("officeName").asText(),
            input.path("phone").asText(null),
            file.id(),
            documentName,
            clock.instant(),
            null,
            null));
  }

  public StoredResponse create(UUID actor, String path, String key, JsonNode input) {
    return idempotency.execute(
        actor.toString(),
        "POST",
        path,
        key,
        StrictJson.stringify(input),
        () -> {
          apply(actor, input, null);
          return new StoredResponse(
              201,
              StrictJson.stringify(get(actor, false)),
              Map.of("Location", "/api/v1/users/me/listing-agent-verification"));
        });
  }
}
