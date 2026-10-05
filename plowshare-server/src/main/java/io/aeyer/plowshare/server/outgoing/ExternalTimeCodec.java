package io.aeyer.plowshare.server.outgoing;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/** The external-work timestamp contract is ISO 8601 text, never a numeric epoch. */
final class ExternalTimeCodec extends JsonDeserializer<Instant> {
  static SimpleModule module() {
    return new SimpleModule().addDeserializer(Instant.class, new ExternalTimeCodec());
  }

  @Override
  public Instant deserialize(JsonParser parser, DeserializationContext context) throws IOException {
    if (!parser.hasToken(JsonToken.VALUE_STRING))
      throw new IOException("External timestamp must be ISO 8601 text");
    String value = parser.getText();
    if (value.length() > 64 || !value.equals(value.strip()))
      throw new IOException("Invalid external timestamp");
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException invalid) {
      throw new IOException("Invalid external timestamp", invalid);
    }
  }
}
