package com.dulleobang.common.json;

import com.dulleobang.common.crypto.Hashes;
import com.dulleobang.common.exception.ApiException;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.erdtman.jcs.JsonCanonicalizer;

public final class StrictJson {
  private static final ObjectMapper MAPPER =
      new ObjectMapper(
              JsonFactory.builder()
                  .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .streamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxNestingDepth(100)
                          .maxStringLength(10_485_760)
                          .build())
                  .build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  private StrictJson() {}

  public static JsonNode parse(byte[] input) {
    try {
      var decoder =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
              .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
      return parse(decoder.decode(java.nio.ByteBuffer.wrap(input)).toString());
    } catch (java.nio.charset.CharacterCodingException ex) {
      throw new ApiException(400, "MALFORMED_JSON");
    }
  }

  public static JsonNode parse(String input) {
    try {
      JsonNode result = MAPPER.readTree(input);
      if (result == null || result.isMissingNode()) throw new IllegalArgumentException();
      numbers(result);
      return result;
    } catch (Exception ex) {
      throw new ApiException(400, "MALFORMED_JSON");
    }
  }

  private static void numbers(JsonNode node) {
    if (node.isFloatingPointNumber() && !Double.isFinite(node.doubleValue()))
      throw new IllegalArgumentException();
    node.elements().forEachRemaining(StrictJson::numbers);
  }

  public static String stringify(Object object) {
    try {
      return MAPPER.writeValueAsString(object);
    } catch (Exception ex) {
      throw new IllegalArgumentException("JSON serialization failed", ex);
    }
  }

  public static String canonical(String json) {
    JsonNode value = parse(json);
    safeIntegers(value);
    try {
      // The bundled JCS parser accepts container roots. RFC 8785 also permits scalars.
      if (!value.isContainerNode()) {
        String wrapped = new JsonCanonicalizer("[" + json + "]").getEncodedString();
        return wrapped.substring(1, wrapped.length() - 1);
      }
      return new JsonCanonicalizer(json).getEncodedString();
    } catch (Exception ex) {
      throw new ApiException(400, "MALFORMED_JSON");
    }
  }

  private static void safeIntegers(JsonNode node) {
    if (node.isIntegralNumber()
        && (!node.canConvertToLong()
            || node.longValue() > 9_007_199_254_740_991L
            || node.longValue() < -9_007_199_254_740_991L))
      throw new ApiException(400, "MALFORMED_JSON");
    node.elements().forEachRemaining(StrictJson::safeIntegers);
  }

  public static String digest(String json) {
    return Hashes.sha256(canonical(json));
  }
}
