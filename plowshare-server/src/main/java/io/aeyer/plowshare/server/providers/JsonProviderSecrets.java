package io.aeyer.plowshare.server.providers;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Reject unknown stored fields before any credential is used. No payload is included in errors. */
public final class JsonProviderSecrets implements ProviderSecretCodec {
  private final ObjectMapper mapper;

  public JsonProviderSecrets(ObjectMapper mapper) {
    this.mapper = ProviderJson.closed(mapper);
  }

  @Override
  public String encode(ProviderSecretMaterial material) {
    try {
      return mapper.writeValueAsString(material);
    } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
      throw new IllegalStateException("Cannot encode credential material");
    }
  }

  @Override
  public ProviderSecretMaterial decode(String value) {
    if (value == null || value.length() > 65536)
      throw new IllegalStateException("Invalid credential material");
    try {
      return mapper.readValue(value, ProviderSecretMaterial.class);
    } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
      throw new IllegalStateException("Invalid stored credential material");
    }
  }
}
