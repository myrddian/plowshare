package io.aeyer.plowshare.server.relay;

import java.util.Objects;

/**
 * A payload row as the persistence boundary holds it. The JSON is decoded only by {@link
 * RelayPayloadCodec}, using the family from the referencing row; it never crosses into a domain
 * contract.
 *
 * @param digest lowercase hex SHA-256 of the application's serialization
 * @param json PostgreSQL's JSONB rendering of the stored payload
 * @param byteLength {@code octet_length} of {@code json}; PostgreSQL's size, not the digest input's
 */
public record RelayStoredPayload(String digest, String json, long byteLength) {
  public RelayStoredPayload {
    Objects.requireNonNull(digest, "digest");
    Objects.requireNonNull(json, "json");
  }
}
