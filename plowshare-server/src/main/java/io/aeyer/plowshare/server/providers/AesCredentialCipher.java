package io.aeyer.plowshare.server.providers;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Fresh 96-bit nonces and AES-256-GCM bind ciphertext to its connection, subject and generation.
 */
public final class AesCredentialCipher implements CredentialCipher {
  private final String active;
  private final Map<String, SecretKeySpec> keys;
  private final SecureRandom random = new SecureRandom();

  public AesCredentialCipher(String active, Map<String, String> encodedKeys) {
    io.aeyer.plowshare.protocol.ProviderCredentials.text(active, 64);
    var checked = new java.util.HashMap<String, SecretKeySpec>();
    if (encodedKeys.isEmpty() || encodedKeys.size() > 16)
      throw new IllegalArgumentException("Configure 1–16 provider encryption keys");
    encodedKeys.forEach(
        (id, encoded) -> {
          io.aeyer.plowshare.protocol.ProviderCredentials.text(id, 64);
          try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            if (bytes.length != 32) throw new IllegalArgumentException();
            checked.put(id, new SecretKeySpec(bytes, "AES"));
            java.util.Arrays.fill(bytes, (byte) 0);
          } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(
                "Provider encryption keys must be base64 AES-256 keys");
          }
        });
    if (!checked.containsKey(active))
      throw new IllegalArgumentException("Active provider encryption key is missing");
    this.active = active;
    this.keys = Map.copyOf(checked);
  }

  @Override
  public Sealed seal(String context, String plaintext) {
    // Three simultaneous 8 KiB OAuth fields can double in size when JSON-escaped.
    if (plaintext == null || plaintext.length() > 65536)
      throw new IllegalArgumentException("Invalid credential material");
    byte[] nonce = new byte[12];
    random.nextBytes(nonce);
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, keys.get(active), new GCMParameterSpec(128, nonce));
      cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
      return new Sealed(
          active,
          Base64.getEncoder().encodeToString(nonce),
          Base64.getEncoder()
              .encodeToString(cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8))));
    } catch (GeneralSecurityException impossible) {
      throw new IllegalStateException("Provider credential encryption unavailable");
    }
  }

  @Override
  public String open(String context, Sealed sealed) {
    var key = keys.get(sealed.keyId());
    if (key == null)
      throw new IllegalStateException(
          "Retain the provider encryption key required by stored credentials");
    try {
      byte[] nonce = Base64.getDecoder().decode(sealed.nonce());
      if (nonce.length != 12) throw new IllegalArgumentException();
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce));
      cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
      return new String(
          cipher.doFinal(Base64.getDecoder().decode(sealed.ciphertext())), StandardCharsets.UTF_8);
    } catch (GeneralSecurityException | IllegalArgumentException invalid) {
      throw new IllegalStateException("Provider credential authentication failed");
    }
  }
}
