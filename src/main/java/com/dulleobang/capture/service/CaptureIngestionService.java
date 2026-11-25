package com.dulleobang.capture.service;

import com.dulleobang.capture.domain.CaptureInputFile;
import com.dulleobang.infrastructure.storage.AssetStorage;
import com.dulleobang.upload.mapper.UploadSourceMapper;
import com.dulleobang.upload.service.UploadIngestService;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class CaptureIngestionService {
  private final UploadIngestService ingestion;
  private final AssetStorage storage;

  public CaptureIngestionService(UploadIngestService ingestion, AssetStorage storage) {
    this.ingestion = ingestion;
    this.storage = storage;
  }

  public UUID ingest(UUID actor, String type, UUID target, List<CaptureInputFile> files) {
    var sources =
        files.stream()
            .map(f -> UploadSourceMapper.capture(f, () -> storage.open(f.path())))
            .toList();
    String purpose = type.equals("LISTING") ? "ROOM_CAPTURE" : "FURNITURE_CAPTURE";
    return ingestion.store(actor, purpose, type, target, sources).session().id();
  }
}
