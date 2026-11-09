package com.dulleobang;

import com.dulleobang.common.crypto.Hashes;
import com.dulleobang.common.json.StrictJson;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.zip.*;
import javax.imageio.ImageIO;

/** Actual JPEG and ARKit ZIP inputs; no mocked upload or worker transport. */
final class P6CaptureFixtures {
  private P6CaptureFixtures() {}

  static byte[] jpeg() throws IOException {
    var image = new BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB);
    try (var out = new ByteArrayOutputStream()) {
      ImageIO.write(image, "JPEG", out);
      return out.toByteArray();
    }
  }

  static List<Double> pose(double x) {
    return List.of(1d, 0d, 0d, 0d, 0d, 1d, 0d, 0d, 0d, 0d, 1d, 0d, x, 0d, 0d, 1d);
  }

  static Map<String, Object> descriptor(UUID id, String name, String mime, byte[] raw) {
    return Map.of(
        "clientFileId",
        id,
        "fileName",
        name,
        "contentType",
        mime,
        "byteSize",
        raw.length,
        "sha256",
        Hashes.sha256(raw));
  }

  static byte[] zip(boolean room, boolean badTimestamp, boolean unsafePath) throws IOException {
    int count = room ? 30 : 12;
    var meta = new LinkedHashMap<String, Object>();
    meta.put("kind", room ? "room" : "furniture");
    meta.put("captureId", UUID.randomUUID());
    meta.put("createdAt", Instant.now().toString());
    meta.put("arSdk", "ARKit");
    meta.put("coordinate", "arkit_world; meters; y-up; right-handed");
    meta.put("imageOrientation", "sensor_native_landscape (not rotated; K matches the JPG)");
    meta.put("frameCount", count);
    meta.put("colorSize", List.of(640, 480));
    meta.put("floorHeightM", 0);
    var poses = new ArrayList<Map<String, Object>>();
    for (int n = 0; n < count; n++)
      poses.add(
          Map.of(
              "frameId",
              n,
              "ts",
              badTimestamp ? 0 : n * .1,
              "tracking",
              "normal",
              "width",
              640,
              "height",
              480,
              "K",
              Map.of("fx", 500, "fy", 500, "cx", 320, "cy", 240),
              "T_wc",
              pose(2 + n * .1)));
    try (var buffer = new ByteArrayOutputStream();
        var zip = new ZipOutputStream(buffer)) {
      entry(zip, "capture/meta.json", StrictJson.stringify(meta).getBytes(StandardCharsets.UTF_8));
      entry(
          zip, "capture/poses.json", StrictJson.stringify(poses).getBytes(StandardCharsets.UTF_8));
      byte[] image = jpeg();
      for (int n = 0; n < count; n++) entry(zip, "capture/frames/" + n + ".jpg", image);
      if (unsafePath) entry(zip, "../outside.txt", new byte[] {1});
      zip.finish();
      return buffer.toByteArray();
    }
  }

  static void entry(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(bytes);
    zip.closeEntry();
  }
}
