package com.dulleobang.compatibility;

import com.dulleobang.common.exception.ApiException;
import com.dulleobang.common.json.StrictJson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipInputStream;

public final class CaptureArchiveProbe {
  public record Result(int entries, int frames, int width, int height) {}

  private CaptureArchiveProbe() {}

  // Bounded P1 parser proof; not connected to production upload until full P6 validation.
  public static Result validate(byte[] raw) {
    try (var zip = new ZipInputStream(new ByteArrayInputStream(raw))) {
      if (raw.length < 4 || raw[0] != 'P' || raw[1] != 'K') throw new IllegalArgumentException();
      Map<String, byte[]> files = new LinkedHashMap<>();
      long expanded = 0;
      for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
        String name = entry.getName();
        if (name.startsWith("/")
            || name.contains("\\")
            || name.contains(":")
            || Arrays.asList(name.split("/")).contains("..")
            || files.containsKey(name)
            || files.size() >= 7000) throw new IllegalArgumentException();
        if (entry.isDirectory()) continue;
        try (var out = new ByteArrayOutputStream()) {
          byte[] chunk = new byte[8192];
          int size;
          while ((size = zip.read(chunk)) != -1) {
            expanded += size;
            if (expanded > 64L * 1024 * 1024) throw new IllegalArgumentException();
            out.write(chunk, 0, size);
          }
          files.put(name, out.toByteArray());
        }
      }
      String metaPath =
          files.keySet().stream()
              .filter(n -> n.equals("meta.json") || n.matches("[^/]+/meta\\.json"))
              .findFirst()
              .orElseThrow();
      String root = metaPath.substring(0, metaPath.length() - 9);
      var meta = StrictJson.parse(new String(files.get(metaPath), StandardCharsets.UTF_8));
      var poses =
          StrictJson.parse(new String(files.get(root + "poses.json"), StandardCharsets.UTF_8));
      if (!meta.path("arSdk").asText().equals("ARKit") || !poses.isArray())
        throw new IllegalArgumentException();
      int count = poses.size(), min = meta.path("kind").asText().equals("furniture") ? 12 : 30;
      if (count < min || count > 1000 || meta.path("frameCount").asInt() != count)
        throw new IllegalArgumentException();
      int width = 0, height = 0;
      double previous = Double.NEGATIVE_INFINITY;
      for (var pose : poses) {
        int id = pose.path("frameId").asInt(-1);
        String imagePath = root + "frames/" + String.format(java.util.Locale.ROOT, "%06d.jpg", id);
        var image = ImageProbe.decode(files.get(imagePath));
        width = image.getWidth();
        height = image.getHeight();
        double ts = pose.path("ts").asDouble(Double.NaN);
        if (width != pose.path("width").asInt()
            || height != pose.path("height").asInt()
            || !Double.isFinite(ts)
            || ts <= previous
            || !pose.path("tracking").asText().equals("normal"))
          throw new IllegalArgumentException();
        previous = ts;
        var k = pose.path("K");
        if (k.path("fx").asDouble() <= 0
            || k.path("fy").asDouble() <= 0
            || k.path("cx").asDouble() < 0
            || k.path("cx").asDouble() >= width
            || k.path("cy").asDouble() < 0
            || k.path("cy").asDouble() >= height) throw new IllegalArgumentException();
        var matrix = pose.path("T_wc");
        if (matrix.size() != 4) throw new IllegalArgumentException();
        for (var row : matrix) if (row.size() != 4) throw new IllegalArgumentException();
        for (int col = 0; col < 4; col++)
          if (matrix.path(3).path(col).asDouble(Double.NaN) != (col == 3 ? 1 : 0))
            throw new IllegalArgumentException();
      }
      return new Result(files.size(), count, width, height);
    } catch (Exception ex) {
      throw new ApiException(422, "INVALID_CAPTURE_ARCHIVE");
    }
  }
}
