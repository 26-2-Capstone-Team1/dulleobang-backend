package com.dulleobang.infrastructure.capture;

import static com.dulleobang.capture.validation.CaptureCoordinates.*;

import com.dulleobang.capture.domain.*;
import com.dulleobang.capture.validation.CaptureManifestNormalizer;
import com.dulleobang.common.exception.ApiException;
import com.dulleobang.common.json.*;
import com.dulleobang.infrastructure.storage.PreparedCapture;
import com.dulleobang.infrastructure.storage.UploadSpool;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.springframework.stereotype.Component;

@Component
public class CaptureZipReader {
  private final UploadSpool spool;
  private final CaptureManifestNormalizer normalizer;

  public CaptureZipReader(UploadSpool spool, CaptureManifestNormalizer normalizer) {
    this.spool = spool;
    this.normalizer = normalizer;
  }

  public PreparedCapture prepare(Path archive, boolean room) {
    Path directory = spool.newDirectory();
    var files = new ArrayList<CaptureInputFile>();
    try {
      ZipDirectoryValidator.validate(archive);
      try (var zip = new ZipFile(archive.toFile(), Charset.forName("IBM437"))) {
        var entries = CaptureArchiveReader.entries(zip);
        normalizer.normalize(new CaptureArchiveContext(zip, entries, directory, files), room);
      }
      return new PreparedCapture(directory, List.copyOf(files));
    } catch (Exception ex) {
      new PreparedCapture(directory, files).close();
      if (ex instanceof ApiException api && api.status() != 400) throw api;
      throw new ApiException(422, "INVALID_CAPTURE_ZIP");
    }
  }
}
