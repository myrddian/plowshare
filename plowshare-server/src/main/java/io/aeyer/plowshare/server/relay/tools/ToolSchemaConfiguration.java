package io.aeyer.plowshare.server.relay.tools;

import io.aeyer.plowshare.protocol.SchemaDefinition;
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;

/** Converts a deployment property into the same typed schema used by live catalogues. */
@Configuration(proxyBeanMethods = false)
public class ToolSchemaConfiguration {
  @Bean
  @ConfigurationPropertiesBinding
  static Converter<String, SchemaDefinition> toolInputSchemaConverter() {
    return new InputSchemaConverter();
  }

  private static final class InputSchemaConverter implements Converter<String, SchemaDefinition> {
    @Override
    public SchemaDefinition convert(String source) {
      return RelayToolCodec.inputSchema(source);
    }
  }
}
