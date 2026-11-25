package com.dulleobang.upload.service;

import com.dulleobang.upload.dto.UploadedFile;
import com.dulleobang.upload.mapper.UploadSourceMapper;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class MultipartStoreService {
  private final UploadIngestService ingestion;

  public MultipartStoreService(UploadIngestService ingestion) {
    this.ingestion = ingestion;
  }

  public List<UUID> store(
      UUID actor, String purpose, String type, UUID target, List<UploadedFile> files) {
    var sources = files.stream().map(UploadSourceMapper::multipart).toList();
    return ingestion.store(actor, purpose, type, target, sources).files().stream()
        .map(f -> f.id())
        .toList();
  }

  public List<Map<String, Object>> fingerprint(List<UploadedFile> files) {
    return files.stream()
        .map(
            f -> {
              Map<String, Object> value = new LinkedHashMap<>();
              value.put("fileName", f.fileName());
              value.put("contentType", f.contentType());
              value.put("byteSize", f.byteSize());
              value.put("sha256", f.sha256());
              return value;
            })
        .toList();
  }
}
