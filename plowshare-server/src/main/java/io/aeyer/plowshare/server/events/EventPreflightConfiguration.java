package io.aeyer.plowshare.server.events;

import java.util.Properties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Standalone database composition for a restored-copy compatibility inventory. This starts no
 * application, runs no migration/recovery and uses PostgreSQL's connection-level read-only mode.
 */
public final class EventPreflightConfiguration {
  private EventPreflightConfiguration() {}

  public static void main(String[] args) {
    if (args.length != 0)
      throw new IllegalArgumentException(
          "Preflight accepts configuration through its explicit environment only");
    String url = required("PLOWSHARE_EVENT_PREFLIGHT_DB_URL");
    if (!url.startsWith("jdbc:postgresql://"))
      throw new IllegalArgumentException(
          "PLOWSHARE_EVENT_PREFLIGHT_DB_URL must explicitly select a PostgreSQL database copy");
    var source =
        databaseCopy(
            url,
            required("PLOWSHARE_EVENT_PREFLIGHT_DB_USER"),
            required("PLOWSHARE_EVENT_PREFLIGHT_DB_PASSWORD"));
    EventCompatibility inventory;
    try {
      inventory = new JdbcFiringStore(new JdbcTemplate(source)).preflight();
    } catch (org.springframework.dao.DataAccessException failed) {
      // Connection and PostgreSQL messages may contain deployment values; never print their cause.
      throw new IllegalStateException(
          "Event preflight could not read the database copy; check configuration, connectivity and read permissions");
    }
    System.out.println(
        "Event DTO preflight: scanned=" + inventory.scanned() + ", invalid=" + inventory.invalid());
    for (var problem : inventory.problems())
      System.out.println(problem.firing() + " " + problem.code());
    inventory.requireCompatible();
  }

  static DriverManagerDataSource databaseCopy(String url, String user, String password) {
    try {
      if (url == null || !url.startsWith("jdbc:postgresql://"))
        throw new IllegalArgumentException();
      var parsed = java.net.URI.create(url.substring("jdbc:".length()));
      if (parsed.getHost() == null
          || parsed.getUserInfo() != null
          || parsed.getFragment() != null
          || parsed.getPath() == null
          || parsed.getPath().length() < 2) throw new IllegalArgumentException();
      if (parsed.getRawQuery() != null) {
        for (String parameter : parsed.getRawQuery().split("&")) {
          String key =
              java.net.URLDecoder.decode(
                  parameter.split("=", 2)[0], java.nio.charset.StandardCharsets.UTF_8);
          // PostgreSQL URL parameters override Properties: options must not disable read-only mode.
          if (java.util.Set.of("options", "user", "password")
              .contains(key.toLowerCase(java.util.Locale.ROOT)))
            throw new IllegalArgumentException();
        }
      }
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException(
          "Preflight URL must name a PostgreSQL database copy without credentials, fragments or options overrides");
    }
    var source = new DriverManagerDataSource(url, user, password);
    var options = new Properties();
    options.setProperty("options", "-c default_transaction_read_only=on");
    source.setConnectionProperties(options);
    return source;
  }

  private static String required(String name) {
    String value = System.getenv(name);
    boolean secret = name.equals("PLOWSHARE_EVENT_PREFLIGHT_DB_PASSWORD");
    if (value == null
        || value.isEmpty()
        || value.length() > 8192
        || value.indexOf('\0') >= 0
        || !secret
            && (value.isBlank()
                || !value.equals(value.strip())
                || value.codePoints().anyMatch(Character::isISOControl)))
      throw new IllegalArgumentException(name + " must be explicitly configured");
    return value;
  }
}
