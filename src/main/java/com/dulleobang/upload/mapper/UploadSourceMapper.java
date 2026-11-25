package com.dulleobang.upload.mapper;

import com.dulleobang.capture.domain.CaptureInputFile;
import com.dulleobang.common.json.StrictJson;
import com.dulleobang.upload.dto.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.core.io.InputStreamSource;

public final class UploadSourceMapper {
  private UploadSourceMapper() {}

  public static UploadSource multipart(UploadedFile file) {
    String name = file.fileName();
    if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,119}")) name = "image.jpg";
    return new UploadSource(
        UUID.randomUUID(),
        name,
        file.contentType(),
        null,
        file.byteSize(),
        file.sha256(),
        file.source());
  }

  public static UploadSource capture(CaptureInputFile file, InputStreamSource source) {
    return new UploadSource(
        file.clientId(),
        file.name(),
        file.contentType(),
        file.role(),
        file.size(),
        file.sha256(),
        source);
  }

  public static JsonNode request(
      String purpose, String type, UUID target, List<UploadSource> files) {
    return StrictJson.parse(
        StrictJson.stringify(
            Map.of(
                "purpose",
                purpose,
                "targetType",
                type,
                "targetId",
                target,
                "files",
                files.stream().map(UploadSourceMapper::descriptor).toList())));
  }

  private static Map<String, Object> descriptor(UploadSource file) {
    var value = new LinkedHashMap<String, Object>();
    value.put("clientFileId", file.clientId());
    value.put("fileName", file.name());
    value.put("contentType", file.contentType());
    value.put("byteSize", file.size());
    value.put("sha256", file.sha256());
    if (file.role() != null) value.put("role", file.role());
    return value;
  }
}
