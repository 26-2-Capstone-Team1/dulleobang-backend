package com.dulleobang.upload.mapper;

import com.dulleobang.common.json.ApiTime;
import com.dulleobang.upload.domain.*;
import java.util.*;

public final class UploadMapper {
  private UploadMapper() {}

  public static AssetFile file(
      UploadSession session,
      com.fasterxml.jackson.databind.JsonNode file,
      String format,
      int ordinal) {
    return new AssetFile(
        UUID.randomUUID(),
        session.owner(),
        session.id(),
        session.targetType(),
        session.targetId(),
        session.purpose(),
        UUID.fromString(file.path("clientFileId").asText()),
        file.path("fileName").asText(),
        file.path("contentType").asText(),
        file.path("byteSize").asLong(),
        file.path("sha256").asText(),
        format,
        file.path("role").asText(),
        ordinal,
        false,
        false,
        null,
        session.createdAt());
  }

  public static Map<String, Object> session(
      UploadSession session, List<AssetFile> files, String status) {
    var mapped =
        files.stream()
            .map(
                f -> {
                  var value = new LinkedHashMap<String, Object>();
                  value.put("clientFileId", f.clientId());
                  value.put("fileName", f.name());
                  value.put("contentType", f.contentType());
                  value.put("byteSize", f.byteSize());
                  value.put("sha256", f.sha256());
                  value.put("fileId", f.id());
                  value.put("role", f.role());
                  value.put("status", f.verified() ? "VERIFIED" : "PENDING");
                  return value;
                })
            .toList();
    return Map.of(
        "uploadSessionId",
        session.id(),
        "purpose",
        session.purpose(),
        "targetType",
        session.targetType(),
        "targetId",
        session.targetId(),
        "status",
        status,
        "files",
        mapped,
        "expiresAt",
        ApiTime.format(session.expiresAt()),
        "createdAt",
        ApiTime.format(session.createdAt()));
  }
}
