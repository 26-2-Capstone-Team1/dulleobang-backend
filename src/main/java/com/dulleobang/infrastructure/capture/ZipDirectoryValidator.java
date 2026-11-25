package com.dulleobang.infrastructure.capture;

import com.dulleobang.common.exception.ApiException;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Coordinates ZIP directory, entry safety and aggregate-size checks. */
public final class ZipDirectoryValidator {
  private ZipDirectoryValidator() {}

  public static void validate(Path path) throws IOException {
    try (var file = new RandomAccessFile(path.toFile(), "r")) {
      var directory = ZipEndReader.read(file);
      file.seek(directory.offset());
      long expanded = 0;
      var names = new HashSet<String>();
      for (int index = 0; index < directory.count(); index++)
        expanded = ZipEntryValidator.read(file, names, expanded);
      if (file.getFilePointer() != directory.offset() + directory.length())
        fail(422, "INVALID_CAPTURE_ZIP");
    }
  }

  static void safe(String name) {
    String stripped = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
    if (stripped.isEmpty()
        || name.startsWith("/")
        || name.indexOf('\\') >= 0
        || name.indexOf(':') >= 0
        || name.chars().anyMatch(c -> c < 32)
        || Arrays.stream(stripped.split("/", -1))
            .anyMatch(s -> s.isEmpty() || s.equals(".") || s.equals("..")))
      fail(422, "UNSAFE_CAPTURE_ZIP");
  }

  static void fail(int status, String code) {
    throw new ApiException(status, code);
  }
}
