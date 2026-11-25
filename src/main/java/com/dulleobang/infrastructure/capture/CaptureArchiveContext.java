package com.dulleobang.infrastructure.capture;

import static com.dulleobang.capture.validation.CaptureCoordinates.*;

import com.dulleobang.capture.domain.*;
import com.dulleobang.common.json.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public record CaptureArchiveContext(
    ZipFile zip, Map<String, ZipEntry> entries, Path directory, List<CaptureInputFile> files) {
  public JsonNode json(String name, boolean required) throws java.io.IOException {
    return CaptureArchiveReader.json(zip, entries, name, required);
  }

  public byte[] bytes(String name, long maximum) throws java.io.IOException {
    return CaptureArchiveReader.bytes(zip, entries.get(name), maximum);
  }

  public UUID add(String name, String role, byte[] supplied) throws java.io.IOException {
    return CaptureArchiveReader.add(zip, entries, directory, files, name, role, supplied);
  }
}
