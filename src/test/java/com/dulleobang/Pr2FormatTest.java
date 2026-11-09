package com.dulleobang;

import static org.assertj.core.api.Assertions.*;

import com.dulleobang.common.contract.ContractValidator;
import com.dulleobang.common.json.StrictJson;
import com.dulleobang.space.mapper.MobileSpaceMapper;
import com.dulleobang.space.validation.PointCloudValidator;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class Pr2FormatTest {
  static byte[] cloud(float x) {
    byte[] header =
        ("ply\nformat binary_little_endian 1.0\nelement vertex 1\n"
                + "property float x\nproperty float y\nproperty float z\n"
                + "property uchar red\nproperty uchar green\nproperty uchar blue\nend_header\n")
            .getBytes(StandardCharsets.US_ASCII);
    return ByteBuffer.allocate(header.length + 15)
        .order(ByteOrder.LITTLE_ENDIAN)
        .put(header)
        .putFloat(x)
        .putFloat(1)
        .putFloat(-2)
        .put(new byte[] {10, 20, 30})
        .array();
  }

  @Test
  void rejectsNonFiniteAndTruncatedPointClouds() {
    assertThat(PointCloudValidator.validate(cloud(1))).isEqualTo(1);
    assertThatThrownBy(() -> PointCloudValidator.validate(cloud(Float.NaN)))
        .isInstanceOf(RuntimeException.class);
    byte[] raw = cloud(1);
    assertThatThrownBy(() -> PointCloudValidator.validate(Arrays.copyOf(raw, raw.length - 1)))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void acceptsOnlyMaterialUnlitExtension() {
    byte[] raw = P7ResultFixtures.glb(List.of("room"));
    var doc = P7ResultFixtures.glbDocument(raw);
    doc.putArray("extensionsUsed").add("KHR_materials_unlit");
    doc.putArray("materials").addObject().putObject("extensions").putObject("KHR_materials_unlit");
    var validator = new com.dulleobang.space.validation.GlbValidator();
    byte[] accepted = P7ResultFixtures.glb(doc, P7ResultFixtures.binary(raw));
    assertThat(validator.validate(accepted, false, null)).contains("room");
    assertThatThrownBy(() -> validator.validate(accepted, true, null))
        .isInstanceOf(RuntimeException.class);
    ((com.fasterxml.jackson.databind.node.ArrayNode) doc.path("extensionsUsed"))
        .add("UNKNOWN_extension");
    assertThatThrownBy(
            () ->
                validator.validate(
                    P7ResultFixtures.glb(doc, P7ResultFixtures.binary(raw)), false, null))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void presentsPointsWithoutCallingThemGaussianSplats() throws Exception {
    var result = P7ResultFixtures.example("RoomReconstructionResult");
    var bundle = (ObjectNode) result.path("bundle");
    ((ObjectNode) bundle.path("sceneAsset")).put("format", "POINT_CLOUD_PLY_V1");
    new ContractValidator().validate("JobCompleteRequest", StrictJson.stringify(result));
    var mobile = MobileSpaceMapper.project(Map.of("bundle", bundle), true);
    assertThat(mobile.get("3dgsUri")).isNull();
    assertThat(mobile.get("pointsUri")).isEqualTo(bundle.path("sceneAsset").path("url").asText());
    var state = MobileSpaceMapper.project(Map.of("bundle", bundle), false);
    assertThat(state.get("sceneUri")).isNull();
    assertThat(state.get("pointsUri")).isEqualTo(mobile.get("pointsUri"));
  }
}
