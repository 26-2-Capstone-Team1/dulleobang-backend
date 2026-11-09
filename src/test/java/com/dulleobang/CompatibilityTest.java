package com.dulleobang;

import static org.assertj.core.api.Assertions.*;

import com.dulleobang.common.config.BackendProperties;
import com.dulleobang.common.contract.ContractValidator;
import com.dulleobang.common.crypto.Hashes;
import com.dulleobang.common.exception.ApiException;
import com.dulleobang.common.json.StrictJson;
import com.dulleobang.common.security.TokenVerifier;
import com.dulleobang.common.version.ETagParser;
import com.dulleobang.compatibility.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

class CompatibilityTest {
  static byte[] bytes(String name) throws Exception {
    try (var input = CompatibilityTest.class.getResourceAsStream("/fixtures/" + name)) {
      return Objects.requireNonNull(input).readAllBytes();
    }
  }

  static final com.fasterxml.jackson.databind.JsonNode fixture;

  static {
    try {
      fixture = StrictJson.parse(new String(bytes("compatibility.json"), StandardCharsets.UTF_8));
    } catch (Exception ex) {
      throw new ExceptionInInitializerError(ex);
    }
  }

  @Test
  void pythonArgonHash() {
    var encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    assertThat(
            encoder.matches(
                fixture.path("password").asText(), fixture.path("passwordHash").asText()))
        .isTrue();
    assertThat(encoder.matches("wrong", fixture.path("passwordHash").asText())).isFalse();
  }

  @Test
  void pythonJwtKindsAndKeySeparation() {
    var keys = fixture.path("secrets");
    var props =
        new BackendProperties(
            keys.path("jwt").asText(),
            keys.path("worker").asText(),
            keys.path("storage").asText(),
            "");
    var verifier =
        new TokenVerifier(
            props,
            Clock.fixed(Instant.ofEpochSecond(fixture.path("epoch").asLong() + 1), ZoneOffset.UTC));
    for (String kind : List.of("user", "worker", "storage", "cursor"))
      assertThat(verifier.verify(fixture.path("tokens").path(kind).asText(), kind)).isNotNull();
    assertThatThrownBy(
            () -> verifier.verify(fixture.path("tokens").path("worker").asText(), "user"))
        .isInstanceOf(ApiException.class);
    var expired =
        new TokenVerifier(
            props,
            Clock.fixed(
                Instant.ofEpochSecond(fixture.path("epoch").asLong() + 2000), ZoneOffset.UTC));
    assertThatThrownBy(() -> expired.verify(fixture.path("tokens").path("user").asText(), "user"))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void pythonCanonicalizationAndHash() {
    for (var item : fixture.path("canonical")) {
      assertThat(StrictJson.canonical(item.path("input").asText()))
          .isEqualTo(item.path("output").asText());
      assertThat(StrictJson.digest(item.path("input").asText()))
          .isEqualTo(item.path("sha256").asText());
    }
  }

  @Test
  void supportsScalarJson() {
    assertThat(StrictJson.canonical(" null ")).isEqualTo("null");
    assertThat(StrictJson.canonical(" true ")).isEqualTo("true");
    assertThat(StrictJson.canonical("1.0")).isEqualTo("1");
    assertThat(StrictJson.canonical("\"text\"")).isEqualTo("\"text\"");
  }

  @Test
  void rejectsMalformedJson() {
    for (String json :
        List.of(
            "{\"x\":1,\"x\":2}", "{\"x\":NaN}", "{\"x\":Infinity}", "{\"x\":1e999}", "{} {}", "")) {
      assertThatThrownBy(() -> StrictJson.parse(json)).as(json).isInstanceOf(ApiException.class);
    }
  }

  @Test
  void checksSafeIntegers() {
    assertThat(StrictJson.canonical("{\"n\":9007199254740991}"))
        .isEqualTo("{\"n\":9007199254740991}");
    assertThatThrownBy(() -> StrictJson.canonical("{\"n\":9007199254740992}"))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> StrictJson.canonical("{\"n\":-9007199254740992}"))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void mobileRequestsRetainAppAliases() throws Exception {
    var validator = new ContractValidator();
    validator.validateMobile("MobilecreateFurniture", "{\"name\":\"bed\",\"category\":\"closet\"}");
    assertThatThrownBy(
            () ->
                validator.validateMobile(
                    "MobilecreateFurniture", "{\"name\":\"bed\",\"category\":\"unknown\"}"))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void exactSchemaTypesAndUnknownFields() throws Exception {
    var validator = new ContractValidator();
    validator.validate("FurnitureCreate", "{\"name\":\"desk\",\"category\":\"DESK\"}");
    for (String json :
        List.of(
            "{\"name\":\"desk\",\"category\":\"desk\"}",
            "{\"name\":\"desk\",\"category\":\"DESK\",\"extra\":1}",
            "{\"name\":42,\"category\":\"DESK\"}",
            "{\"name\":null,\"category\":\"DESK\"}")) {
      assertThatThrownBy(() -> validator.validate("FurnitureCreate", json))
          .isInstanceOf(ApiException.class);
    }
  }

  @Test
  void depthAndConfidenceSamples() throws Exception {
    assertThat(ImageProbe.pngSamples(bytes("depth.png"), 16, false))
        .containsExactly(0, 1200, 5000, 65535);
    assertThat(ImageProbe.pngSamples(bytes("confidence.png"), 8, true)).containsExactly(0, 1, 2, 2);
    assertThatThrownBy(() -> ImageProbe.pngSamples(bytes("depth.png"), 8, true))
        .isInstanceOf(ApiException.class);
    var bad = new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_BYTE_GRAY);
    bad.getRaster().setSample(0, 0, 0, 3);
    var out = new ByteArrayOutputStream();
    javax.imageio.ImageIO.write(bad, "PNG", out);
    assertThatThrownBy(() -> ImageProbe.pngSamples(out.toByteArray(), 8, true))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void removesPhotoMetadata() throws Exception {
    byte[] original = bytes("photo.jpg"), clean = ImageProbe.sanitizedJpeg(original);
    assertThat(new String(original, StandardCharsets.ISO_8859_1))
        .contains("synthetic fixture artist");
    assertThat(new String(clean, StandardCharsets.ISO_8859_1))
        .doesNotContain("synthetic fixture artist", "Exif");
    assertThat(ImageProbe.decode(clean).getWidth()).isEqualTo(8);
    assertThatThrownBy(() -> ImageProbe.decode(new byte[] {1, 2})).isInstanceOf(ApiException.class);
  }

  @Test
  void readsActualPythonZip() throws Exception {
    var result = CaptureArchiveProbe.validate(bytes("capture.zip"));
    assertThat(result.frames()).isEqualTo(30);
    assertThat(result.width()).isEqualTo(640);
    assertThatThrownBy(() -> CaptureArchiveProbe.validate(new byte[] {1, 2}))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void rejectsZipTraversal() throws Exception {
    var out = new ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(out)) {
      zip.putNextEntry(new ZipEntry("../escape"));
      zip.write(new byte[] {1});
      zip.closeEntry();
    }
    assertThatThrownBy(() -> CaptureArchiveProbe.validate(out.toByteArray()))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void validatesPlyBytesNotOnlyExtension() throws Exception {
    byte[] good = bytes("scene.ply");
    assertThat(PlyProbe.validate(good)).isEqualTo(1);
    assertThatThrownBy(() -> PlyProbe.validate(Arrays.copyOf(good, good.length - 1)))
        .isInstanceOf(ApiException.class);
    byte[] bad = good.clone();
    java.nio.ByteBuffer.wrap(bad, bad.length - 56, 4)
        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        .putFloat(Float.NaN);
    assertThatThrownBy(() -> PlyProbe.validate(bad)).isInstanceOf(ApiException.class);
  }

  @Test
  void validatesGlbActualBoundsAndLength() throws Exception {
    byte[] good = bytes("model.glb");
    var box = GlbProbe.validate(good);
    assertThat(box.minX()).isEqualTo(-0.5f);
    assertThat(box.maxY()).isEqualTo(1);
    assertThatThrownBy(() -> GlbProbe.validate(Arrays.copyOf(good, good.length - 1)))
        .isInstanceOf(ApiException.class);
    byte[] bad = good.clone();
    bad[0] = 0;
    assertThatThrownBy(() -> GlbProbe.validate(bad)).isInstanceOf(ApiException.class);
  }

  @Test
  void glbRequiresDeclaredBounds() throws Exception {
    byte[] good = bytes("model.glb");
    String encoded = new String(good, StandardCharsets.ISO_8859_1).replace("\"min\"", "\"bad\"");
    assertThatThrownBy(() -> GlbProbe.validate(encoded.getBytes(StandardCharsets.ISO_8859_1)))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void rejectsInvalidUtf8Bytes() {
    byte[] bad = {
      (byte) '{',
      (byte) '"',
      (byte) 'x',
      (byte) '"',
      (byte) ':',
      (byte) '"',
      (byte) 0xc3,
      (byte) 0x28,
      (byte) '"',
      (byte) '}'
    };
    assertThatThrownBy(() -> StrictJson.parse(bad)).isInstanceOf(ApiException.class);
  }

  @Test
  void etagContract() {
    assertThat(ETagParser.requireMatch("\"3\"", 3)).isEqualTo(3);
    assertThatThrownBy(() -> ETagParser.requireMatch(null, 3))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(428));
    for (String value : List.of("\"2\"", "W/\"3\"", "*"))
      assertThatThrownBy(() -> ETagParser.requireMatch(value, 3))
          .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(412));
    ETagParser.requireCreate("*", false);
    assertThatThrownBy(() -> ETagParser.requireCreate("*", true)).isInstanceOf(ApiException.class);
  }

  @Test
  void fixturesMatchRecordedHashes() throws Exception {
    var hashes = StrictJson.parse(new String(bytes("sha256.json"), StandardCharsets.UTF_8));
    var fields = hashes.fields();
    while (fields.hasNext()) {
      var entry = fields.next();
      assertThat(Hashes.sha256(bytes(entry.getKey()))).isEqualTo(entry.getValue().asText());
    }
  }
}
