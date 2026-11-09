package com.dulleobang;

import static org.assertj.core.api.Assertions.*;

import com.dulleobang.common.exception.ApiException;
import com.dulleobang.space.mapper.MobileSpaceMapper;
import com.dulleobang.space.validation.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ProductionGeometryTest {
  @Test
  void rejectsInvalidMeshes() throws Exception {
    var validator = new GlbValidator();
    byte[] valid = P7ResultFixtures.glb(List.of("mesh"));
    var box = P7ResultFixtures.example("FurnitureReconstructionResult").path("boundingBox");
    assertThat(validator.validate(valid, false, box)).containsExactly("mesh");
    var doc = P7ResultFixtures.glbDocument(valid);
    ((ObjectNode) doc.path("nodes").get(0)).putArray("children").add(0);
    assertThatThrownBy(
            () ->
                validator.validate(
                    P7ResultFixtures.glb(doc, P7ResultFixtures.binary(valid)), true, null))
        .isInstanceOf(ApiException.class);
    var empty = P7ResultFixtures.glbDocument(valid);
    ((ObjectNode) empty.path("nodes").get(0)).remove("mesh");
    assertThatThrownBy(
            () ->
                validator.validate(
                    P7ResultFixtures.glb(empty, P7ResultFixtures.binary(valid)), true, null))
        .isInstanceOf(ApiException.class);
    byte[] nonfinite = P7ResultFixtures.binary(valid);
    java.nio.ByteBuffer.wrap(nonfinite).order(java.nio.ByteOrder.LITTLE_ENDIAN).putFloat(Float.NaN);
    assertThatThrownBy(
            () ->
                validator.validate(
                    P7ResultFixtures.glb(P7ResultFixtures.glbDocument(valid), nonfinite),
                    false,
                    box))
        .isInstanceOf(ApiException.class);
    assertThat(PlyValidator.validate(P7ResultFixtures.ply())).isEqualTo(1);
    byte[] bad = P7ResultFixtures.ply();
    bad[bad.length - 16] = 0;
    bad[bad.length - 15] = 0;
    bad[bad.length - 14] = 0;
    bad[bad.length - 13] = 0;
    assertThatThrownBy(() -> PlyValidator.validate(bad)).isInstanceOf(ApiException.class);
  }

  @Test
  void mapsMobileGeometry() throws Exception {
    var bundle = P7ResultFixtures.example("SpaceBundle");
    var room = (ObjectNode) bundle.path("geometry").path("rooms").get(0);
    room.put("floorY", -1.42).put("ceilingY", .98);
    var converted = MobileSpaceMapper.geometry(bundle.path("geometry"));
    assertThat((Double) converted.get("ceilingHeight")).isCloseTo(2.4, within(1e-9));
    room.put("floorY", 1).put("ceilingY", 3.4);
    converted = MobileSpaceMapper.geometry(bundle.path("geometry"));
    assertThat((Double) converted.get("ceilingHeight")).isCloseTo(2.4, within(1e-9));
    var second = room.deepCopy();
    second.put("roomId", UUID.randomUUID().toString()).put("floorY", -1.42).put("ceilingY", 4);
    ((ArrayNode) bundle.path("geometry").path("rooms")).add(second);
    assertThat((Double) MobileSpaceMapper.geometry(bundle.path("geometry")).get("ceilingHeight"))
        .isCloseTo(5.42, within(1e-9));
    var object = (ObjectNode) bundle.path("objects").get(0);
    ((ObjectNode) object.path("transform"))
        .putObject("rotation")
        .put("x", Math.sqrt(.5))
        .put("y", 0)
        .put("z", 0)
        .put("w", Math.sqrt(.5));
    var result = MobileSpaceMapper.object(object);
    var center = (Map<?, ?>) result.get("position");
    assertThat((Double) center.get("y")).isCloseTo(0, within(1e-9));
    assertThat((Double) center.get("z")).isCloseTo(-.625, within(1e-9));
  }
}
