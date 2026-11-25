package com.dulleobang.infrastructure.capture;

import static com.dulleobang.capture.validation.CaptureCoordinates.*;

import com.dulleobang.capture.domain.*;
import com.dulleobang.common.crypto.Hashes;
import com.dulleobang.common.exception.ApiException;
import com.dulleobang.common.json.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public final class CaptureArchiveReader {
  private CaptureArchiveReader() {}

  static Map<String, ZipEntry> entries(ZipFile zip) {
    Map<String, ZipEntry> all = new LinkedHashMap<>();
    zip.stream()
        .forEach(
            entry -> {
              String name = entry.getName();
              ZipDirectoryValidator.safe(name);
              if (!entry.isDirectory()
                  && !name.startsWith("__MACOSX/")
                  && !name.substring(name.lastIndexOf('/') + 1).equals(".DS_Store"))
                all.put(name, entry);
            });
    var roots =
        all.keySet().stream()
            .filter(n -> n.substring(n.lastIndexOf('/') + 1).equals("meta.json"))
            .map(n -> n.substring(0, n.length() - 9))
            .toList();
    require(roots.size() == 1, "INVALID_CAPTURE_ZIP");
    String root = roots.get(0);
    Map<String, ZipEntry> result = new LinkedHashMap<>();
    all.forEach(
        (name, entry) -> {
          require(name.startsWith(root), "INVALID_CAPTURE_ZIP");
          result.put(name.substring(root.length()), entry);
        });
    return result;
  }

  static byte[] bytes(ZipFile zip, ZipEntry entry, long maximum) throws java.io.IOException {
    require(entry != null, "MISSING_CAPTURE_FILE");
    if (entry.getSize() > maximum) throw new ApiException(413, "CAPTURE_ZIP_TOO_LARGE");
    try (var input = zip.getInputStream(entry)) {
      byte[] raw = input.readNBytes((int) maximum + 1);
      if (raw.length > maximum) throw new ApiException(413, "CAPTURE_ZIP_TOO_LARGE");
      var crc = new CRC32();
      crc.update(raw);
      require(
          raw.length == entry.getSize() && crc.getValue() == entry.getCrc(), "INVALID_CAPTURE_ZIP");
      return raw;
    }
  }

  static JsonNode json(ZipFile zip, Map<String, ZipEntry> entries, String name, boolean required)
      throws java.io.IOException {
    if (!entries.containsKey(name)) {
      require(!required, "MISSING_CAPTURE_FILE");
      return null;
    }
    return StrictJson.parse(bytes(zip, entries.get(name), 10L * 1024 * 1024));
  }

  static UUID add(
      ZipFile zip,
      Map<String, ZipEntry> entries,
      Path directory,
      List<CaptureInputFile> files,
      String name,
      String role,
      byte[] supplied)
      throws java.io.IOException {
    byte[] raw = supplied == null ? bytes(zip, entries.get(name), 20L * 1024 * 1024) : supplied;
    UUID id = UUID.randomUUID();
    Path path = directory.resolve(id.toString());
    String suffix = name.substring(name.lastIndexOf('.')).toLowerCase(Locale.ROOT),
        mime =
            suffix.equals(".json")
                ? "application/json"
                : suffix.equals(".png") ? "image/png" : "image/jpeg";
    long limit = mime.equals("application/json") ? 10L * 1024 * 1024 : 20L * 1024 * 1024;
    if (raw.length > limit) throw new ApiException(413, "CAPTURE_ZIP_TOO_LARGE");
    var file =
        new CaptureInputFile(
            id,
            role.toLowerCase(Locale.ROOT) + "_" + id + suffix,
            mime,
            role,
            raw.length,
            Hashes.sha256(raw),
            path);
    files.add(file);
    Files.write(path, raw, StandardOpenOption.CREATE_NEW);
    return id;
  }

  private static void require(boolean condition, String code) {
    if (!condition) throw new ApiException(422, code);
  }
}
