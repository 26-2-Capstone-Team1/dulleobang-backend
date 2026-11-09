package com.dulleobang.compatibility;

import com.dulleobang.common.exception.ApiException;
import com.dulleobang.common.json.StrictJson;
import java.nio.*;

public final class GlbProbe {
  public record Bounds(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {}

  private GlbProbe() {}

  // P1 static embedded POSITION subset only. Full material/node/collision policy belongs to P6/P7.
  public static Bounds validate(byte[] raw) {
    try {
      var file = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
      if (file.getInt() != 0x46546c67 || file.getInt() != 2 || file.getInt() != raw.length)
        throw new IllegalArgumentException();
      int length = file.getInt();
      if (length < 0 || length % 4 != 0 || file.getInt() != 0x4e4f534a || length > file.remaining())
        throw new IllegalArgumentException();
      byte[] json = new byte[length];
      file.get(json);
      var doc = StrictJson.parse(json);
      if (!doc.path("asset").path("version").asText().equals("2.0"))
        throw new IllegalArgumentException();
      int binLength = file.getInt();
      if (file.getInt() != 0x004e4942 || binLength != file.remaining() || binLength % 4 != 0)
        throw new IllegalArgumentException();
      byte[] binary = new byte[binLength];
      file.get(binary);
      var buffer = doc.path("buffers");
      if (buffer.size() != 1
          || buffer.get(0).has("uri")
          || buffer.get(0).path("byteLength").asInt() <= 0
          || buffer.get(0).path("byteLength").asInt() > binLength)
        throw new IllegalArgumentException();
      if (doc.path("extensionsRequired").size() > 0
          || doc.path("animations").size() > 0
          || doc.path("skins").size() > 0) throw new IllegalArgumentException();
      float[] min = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY};
      float[] max = {Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
      int vertices = 0;
      for (var mesh : doc.path("meshes"))
        for (var primitive : mesh.path("primitives")) {
          var accessor =
              doc.path("accessors").get(primitive.path("attributes").path("POSITION").asInt(-1));
          if (accessor == null
              || accessor.path("componentType").asInt() != 5126
              || !accessor.path("type").asText().equals("VEC3")
              || accessor.path("min").size() != 3
              || accessor.path("max").size() != 3
              || accessor.has("sparse")) throw new IllegalArgumentException();
          var view = doc.path("bufferViews").get(accessor.path("bufferView").asInt(-1));
          if (view == null || view.path("buffer").asInt() != 0)
            throw new IllegalArgumentException();
          int count = accessor.path("count").asInt(), stride = view.path("byteStride").asInt(12);
          int start = view.path("byteOffset").asInt() + accessor.path("byteOffset").asInt();
          long end = (long) start + (long) (count - 1) * stride + 12;
          if (count < 1
              || stride < 12
              || start < 0
              || end > binary.length
              || end > (long) view.path("byteOffset").asInt() + view.path("byteLength").asInt())
            throw new IllegalArgumentException();
          var positions = ByteBuffer.wrap(binary).order(ByteOrder.LITTLE_ENDIAN);
          float[] localMin = {
            Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY
          };
          float[] localMax = {
            Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY
          };
          for (int n = 0; n < count; n++)
            for (int axis = 0; axis < 3; axis++) {
              float v = positions.getFloat(start + n * stride + axis * 4);
              if (!Float.isFinite(v)) throw new IllegalArgumentException();
              localMin[axis] = Math.min(localMin[axis], v);
              localMax[axis] = Math.max(localMax[axis], v);
            }
          for (int axis = 0; axis < 3; axis++)
            if (!Double.isFinite(accessor.path("min").path(axis).asDouble(Double.NaN))
                || !Double.isFinite(accessor.path("max").path(axis).asDouble(Double.NaN))
                || Math.abs(localMin[axis] - accessor.path("min").path(axis).asDouble(Double.NaN))
                    > 0.001
                || Math.abs(localMax[axis] - accessor.path("max").path(axis).asDouble(Double.NaN))
                    > 0.001) throw new IllegalArgumentException();
          for (int axis = 0; axis < 3; axis++) {
            min[axis] = Math.min(min[axis], localMin[axis]);
            max[axis] = Math.max(max[axis], localMax[axis]);
          }
          vertices += count;
        }
      if (vertices == 0) throw new IllegalArgumentException();
      return new Bounds(min[0], min[1], min[2], max[0], max[1], max[2]);
    } catch (Exception ex) {
      throw new ApiException(422, "INVALID_ARTIFACT");
    }
  }
}
