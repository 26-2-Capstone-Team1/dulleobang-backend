package com.dulleobang.common.crypto;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class Hashes {
  private Hashes() {}

  public static String sha256(byte[] bytes) {
    return HexFormat.of().formatHex(digest().digest(bytes));
  }

  public static String sha256(String value) {
    return sha256(value.getBytes(StandardCharsets.UTF_8));
  }

  public static String sha256(InputStream input) throws IOException {
    var hash = digest();
    byte[] buffer = new byte[65536];
    int count;
    while ((count = input.read(buffer)) != -1) hash.update(buffer, 0, count);
    return HexFormat.of().formatHex(hash.digest());
  }

  private static MessageDigest digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (java.security.NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }
}
