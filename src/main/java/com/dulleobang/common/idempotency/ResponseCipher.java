package com.dulleobang.common.idempotency;

import com.dulleobang.common.config.BackendProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class ResponseCipher {
  private final SecretKeySpec key;
  private final SecureRandom random = new SecureRandom();

  public ResponseCipher(BackendProperties properties) throws Exception {
    key =
        new SecretKeySpec(
            MessageDigest.getInstance("SHA-256")
                .digest(properties.storageSecret().getBytes(StandardCharsets.UTF_8)),
            "AES");
  }

  public byte[] encrypt(String text, String binding) {
    try {
      byte[] nonce = new byte[12];
      random.nextBytes(nonce);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
      cipher.updateAAD(binding.getBytes(StandardCharsets.UTF_8));
      byte[] encoded = cipher.doFinal(text.getBytes(StandardCharsets.UTF_8));
      byte[] out = Arrays.copyOf(nonce, nonce.length + encoded.length);
      System.arraycopy(encoded, 0, out, nonce.length, encoded.length);
      return out;
    } catch (Exception ex) {
      throw new IllegalStateException("Response encryption failed", ex);
    }
  }

  public String decrypt(byte[] bytes, String binding) {
    try {
      if (bytes.length < 28) throw new IllegalArgumentException();
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Arrays.copyOf(bytes, 12)));
      cipher.updateAAD(binding.getBytes(StandardCharsets.UTF_8));
      return new String(
          cipher.doFinal(Arrays.copyOfRange(bytes, 12, bytes.length)), StandardCharsets.UTF_8);
    } catch (Exception ex) {
      throw new IllegalStateException("Stored response authentication failed", ex);
    }
  }
}
