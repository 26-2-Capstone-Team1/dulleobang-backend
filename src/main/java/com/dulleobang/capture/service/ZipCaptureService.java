package com.dulleobang.capture.service;

import com.dulleobang.common.idempotency.*;
import com.dulleobang.common.idempotency.service.IdempotencyService;
import com.dulleobang.common.json.StrictJson;
import com.dulleobang.infrastructure.capture.CaptureZipReader;
import com.dulleobang.infrastructure.storage.*;
import com.dulleobang.upload.domain.AssetFile;
import com.dulleobang.upload.repository.UploadRepository;
import com.dulleobang.upload.service.UploadAccessService;
import java.io.InputStream;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class ZipCaptureService {
  private final UploadSpool spool;
  private final CaptureZipReader validator;
  private final CaptureIngestionService ingestion;
  private final CaptureSubmissionService submissions;
  private final UploadAccessService access;
  private final UploadRepository uploads;
  private final AssetStorage storage;
  private final IdempotencyService idempotency;
  private final Clock clock;

  public ZipCaptureService(
      UploadSpool spool,
      CaptureZipReader validator,
      CaptureIngestionService ingestion,
      CaptureSubmissionService submissions,
      UploadAccessService access,
      UploadRepository uploads,
      AssetStorage storage,
      IdempotencyService idempotency,
      Clock clock) {
    this.spool = spool;
    this.validator = validator;
    this.ingestion = ingestion;
    this.submissions = submissions;
    this.access = access;
    this.uploads = uploads;
    this.storage = storage;
    this.idempotency = idempotency;
    this.clock = clock;
  }

  public StoredResponse submit(
      UUID actor, String type, UUID id, String path, String key, InputStream input) {
    access.owned(actor, type, id);
    try (var archive = spool.copy(input, 2L * 1024 * 1024 * 1024)) {
      return idempotency.execute(
          actor.toString(),
          "POST",
          path,
          key,
          StrictJson.stringify(
              Map.of("archiveSha256", archive.sha256(), "byteSize", archive.size())),
          () -> {
            submissions.reserve(actor, type, id);
            access.owned(actor, type, id);
          },
          () -> {
            try (var prepared = validator.prepare(archive.path(), type.equals("LISTING"))) {
              UUID session = ingestion.ingest(actor, type, id, prepared.files());
              var file =
                  new AssetFile(
                      UUID.randomUUID(),
                      actor,
                      null,
                      type,
                      id,
                      "CAPTURE_ARCHIVE",
                      UUID.randomUUID(),
                      "capture.zip",
                      "application/zip",
                      archive.size(),
                      archive.sha256(),
                      "CAPTURE_ZIP_V1",
                      "ARCHIVE",
                      0,
                      true,
                      true,
                      null,
                      clock.instant());
              uploads.lockBlob(archive.sha256());
              storage.promote(
                  new AssetStorage.Staged() {
                    public java.nio.file.Path path() {
                      return archive.path();
                    }

                    public void close() {}
                  },
                  archive.sha256());
              uploads.add(file);
              return submissions.accept(actor, type, id, session, file.id());
            }
          });
    }
  }
}
