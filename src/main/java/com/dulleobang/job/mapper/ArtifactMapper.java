package com.dulleobang.job.mapper;

import com.dulleobang.job.domain.JobRecord;
import com.dulleobang.upload.domain.AssetFile;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.*;

public final class ArtifactMapper {
  private ArtifactMapper() {}

  public static AssetFile file(JobRecord job, JsonNode info, int ordinal, Instant now) {
    String role = info.path("role").asText();
    return new AssetFile(
        UUID.randomUUID(),
        job.owner(),
        null,
        job.targetType(),
        job.targetId(),
        "RESULT",
        UUID.fromString(info.path("clientFileId").asText()),
        role.toLowerCase(Locale.ROOT) + ".bin",
        info.path("contentType").asText(),
        info.path("byteSize").asLong(),
        info.path("sha256").asText(),
        info.path("format").asText(),
        role,
        ordinal,
        false,
        false,
        null,
        now);
  }
}
