package io.aeyer.plowshare.server.providers;

import java.util.Objects;

/** Authenticated encryption with externally supplied key versions and record-specific AAD. */
public interface CredentialCipher {
  record Sealed(String keyId, String nonce, String ciphertext) {
    public Sealed {
      io.aeyer.plowshare.protocol.ProviderCredentials.text(keyId, 64);
      Objects.requireNonNull(nonce);
      Objects.requireNonNull(ciphertext);
      if (nonce.length() != 16 || ciphertext.length() < 24 || ciphertext.length() > 131072)
        throw new IllegalArgumentException("Invalid sealed credential");
    }

    @Override
    public String toString() {
      return "Sealed[keyId=" + keyId + ", ciphertext=<redacted>]";
    }
  }

  Sealed seal(String context, String plaintext);

  String open(String context, Sealed sealed);
}
