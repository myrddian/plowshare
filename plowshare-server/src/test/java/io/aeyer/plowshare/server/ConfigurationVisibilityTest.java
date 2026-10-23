package io.aeyer.plowshare.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.cache.ProjectionCacheProperties;
import io.aeyer.plowshare.server.pool.PoolAdmissionProperties;
import io.aeyer.plowshare.server.ws.EventChannelConfig;
import io.aeyer.plowshare.server.ws.EventChannelHandler;
import io.aeyer.plowshare.server.ws.WebSocketKeepAliveProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Pins the shipped {@code application.yml} to the Java defaults of the tuning parameters the server
 * actually has. A parameter that lives only as a Java default is invisible to an operator; one
 * declared in the file with a different value than the Java default silently changes behaviour.
 * Both failures are invisible to a test that binds an inline fixture, so every assertion reads the
 * real shipped file, as {@link DatasourceConfigurationTest} does.
 *
 * <p>Java is the source of truth and nothing is restated here. The expected key sets come from the
 * record components of {@link PoolAdmissionProperties} and {@link ProjectionCacheProperties}, and
 * their defaults from binding the record with no properties. The {@code plowshare.transport.*} keys
 * and defaults come from reflection over the {@link Value} fields of {@link EventChannelConfig} and
 * the {@link Scheduled} delay strings of {@link EventChannelHandler}.
 *
 * <p>The {@code plowshare.transport.keepalive.*} block is pinned as a third record, including the
 * check-interval default repeated by {@code ScheduledWebSocketKeepAlive}.
 *
 * <p>Not pinned: transport keys declared in some other class (a new {@code @Value} elsewhere is not
 * discovered). A transport placeholder the parser cannot read fails the test rather than skipping.
 */
class ConfigurationVisibilityTest {

  private static final Pattern PLACEHOLDER = Pattern.compile("^\\$\\{([^:}]+):(.*)\\}$");
  private static final String TRANSPORT_PREFIX = "plowshare.transport.";

  private final MutablePropertySources shipped = load();

  private static MutablePropertySources load() {
    try {
      var sources = new MutablePropertySources();
      new YamlPropertySourceLoader()
          .load("application", new ClassPathResource("application.yml"))
          .forEach(sources::addLast);
      return sources;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The raw, unresolved text of a shipped key, failing the test when it is not declared. */
  private String declared(String key) {
    for (var source : shipped) {
      if (source.containsProperty(key)) {
        return String.valueOf(source.getProperty(key));
      }
    }
    throw new AssertionError(key + " is not declared in the shipped application.yml");
  }

  /** Direct child keys declared in the shipped file under {@code prefix} (ending in a dot). */
  private Set<String> declaredUnder(String prefix) {
    var keys = new TreeSet<String>();
    for (var source : shipped) {
      if (source instanceof EnumerablePropertySource<?> enumerable) {
        for (String name : enumerable.getPropertyNames()) {
          if (name.startsWith(prefix)) {
            keys.add(name.substring(prefix.length()));
          }
        }
      }
    }
    return keys;
  }

  private Binder shippedBinder() {
    return new Binder(
        ConfigurationPropertySources.from(shipped),
        new PropertySourcesPlaceholdersResolver(shipped));
  }

  /** A binder over no properties at all, so a bound record carries only its Java defaults. */
  private static Binder emptyBinder() {
    var empty = new MutablePropertySources();
    empty.addLast(new MapPropertySource("empty", new LinkedHashMap<>()));
    return new Binder(ConfigurationPropertySources.from(empty));
  }

  /** Kebab-case property names of every bound component of a properties record. */
  private static Set<String> componentKeys(Class<? extends Record> type) {
    return Arrays.stream(type.getRecordComponents())
        .map(RecordComponent::getName)
        .map(ConfigurationVisibilityTest::kebab)
        .collect(Collectors.toCollection(TreeSet::new));
  }

  private static String kebab(String camel) {
    return camel.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase();
  }

  private static String envName(String envPrefix, String key) {
    return (envPrefix + key).toUpperCase().replace('-', '_');
  }

  /**
   * Transport keys and Java defaults, read from the annotations that actually supply them: each
   * {@code @Value("${key:default}")} field of {@link EventChannelConfig} and the placeholder
   * attributes of every {@code @Scheduled} method of {@link EventChannelHandler}.
   */
  private static Map<String, String> javaTransportDefaults() {
    var found = new TreeMap<String, String>();
    for (var field : EventChannelConfig.class.getDeclaredFields()) {
      Value value = field.getAnnotation(Value.class);
      if (value != null) {
        record(
            found, EventChannelConfig.class.getSimpleName() + "." + field.getName(), value.value());
      }
    }
    for (Method method : EventChannelHandler.class.getDeclaredMethods()) {
      Scheduled scheduled = method.getAnnotation(Scheduled.class);
      if (scheduled != null) {
        String owner = EventChannelHandler.class.getSimpleName() + "." + method.getName();
        record(found, owner + " fixedDelayString", scheduled.fixedDelayString());
        record(found, owner + " initialDelayString", scheduled.initialDelayString());
      }
    }
    return found;
  }

  /**
   * Records one annotation string. A string that is not a transport key is ignored, but one that
   * mentions a transport key in a form this parser cannot read (no default, stray whitespace, a
   * SpEL expression, a nested placeholder) fails loudly: skipping it would drop the key from the
   * Java set and leave this guard blind to exactly the parameter it exists to check.
   */
  private static void record(Map<String, String> found, String where, String expression) {
    if (!expression.contains(TRANSPORT_PREFIX)) {
      return;
    }
    Matcher m = PLACEHOLDER.matcher(expression);
    if (!m.matches() || !m.group(1).startsWith(TRANSPORT_PREFIX)) {
      throw new AssertionError(
          "unparseable transport placeholder on "
              + where
              + ": '"
              + expression
              + "' must have the form ${plowshare.transport.<key>:<default>}");
    }
    String previous = found.put(m.group(1).substring(TRANSPORT_PREFIX.length()), m.group(2));
    if (previous != null) {
      assertEquals(previous, m.group(2), "Java declares two defaults for " + m.group(1));
    }
  }

  private void assertRecordMatchesFile(String block, Class<? extends Record> type) {
    assertRecordMatchesFile(
        block, "PLOWSHARE_" + block.toUpperCase().replace('-', '_') + "_", type);
  }

  /** {@code envPrefix} is explicit where legacy environment names do not follow the key path. */
  private void assertRecordMatchesFile(
      String block, String envPrefix, Class<? extends Record> type) {
    String prefix = "plowshare." + block + ".";
    assertEquals(
        componentKeys(type),
        declaredUnder(prefix),
        "keys declared in application.yml must be exactly the components of "
            + type.getSimpleName());
    for (String key : componentKeys(type)) {
      assertOverride(prefix + key, envName(envPrefix, key), null);
    }
  }

  @Test
  void every_pool_admission_key_is_declared_in_application_yml() {
    assertRecordMatchesFile("pool-admission", PoolAdmissionProperties.class);
    var fromFile =
        shippedBinder()
            .bindOrCreate("plowshare.pool-admission", Bindable.of(PoolAdmissionProperties.class));
    var javaDefaults =
        emptyBinder()
            .bindOrCreate("plowshare.pool-admission", Bindable.of(PoolAdmissionProperties.class));
    assertEquals(javaDefaults, fromFile);
    // The invariant the file documents must hold, against the timeout the file itself ships.
    long timeoutMillis =
        Long.parseLong(
            new PropertySourcesPlaceholdersResolver(shipped)
                .resolvePlaceholders(declared("spring.datasource.hikari.connection-timeout"))
                .toString());
    fromFile.validateAgainst(Duration.ofMillis(timeoutMillis));
    // The pool size must come from the file too. Hard-coding 20 here would let the shipped default
    // fall to a size the throttle cannot derive a curve from while this test still passed.
    int poolSize =
        Integer.parseInt(
            new PropertySourcesPlaceholdersResolver(shipped)
                .resolvePlaceholders(declared("spring.datasource.hikari.maximum-pool-size"))
                .toString());
    // The shipped pool size must leave a derivable curve: permits(), and threshold() in
    // particular, throw for a pool too small, exactly as the server would refuse to boot.
    int permits = fromFile.permits(poolSize);
    assertEquals(poolSize - fromFile.permitMargin(), permits);
    assertTrue(
        permits - fromFile.threshold(poolSize) >= 2,
        "the shipped pool size must leave at least two permits above the threshold");
  }

  @Test
  void pool_admission_derived_values_are_not_settable_keys() {
    Set<String> bound = componentKeys(PoolAdmissionProperties.class);
    Set<String> settable = declaredUnder("plowshare.pool-admission.");
    assertEquals(bound, settable);
    for (String derived : new String[] {"permits", "threshold"}) {
      assertFalse(bound.contains(derived), derived + " must be derived, not a bound component");
      assertFalse(settable.contains(derived), derived + " must be derived, not a yml key");
    }
  }

  @Test
  void every_transport_dispatch_key_is_declared_in_application_yml() {
    Map<String, String> javaDefaults = javaTransportDefaults();
    assertFalse(javaDefaults.isEmpty(), "no transport defaults found by reflection");
    var resolver = new PropertySourcesPlaceholdersResolver(shipped);
    var inFile = new TreeSet<>(declaredUnder(TRANSPORT_PREFIX));
    inFile.removeIf(key -> key.startsWith("keepalive."));
    assertEquals(javaDefaults.keySet(), inFile, "transport keys in Java and in application.yml");
    javaDefaults.forEach(
        (key, javaDefault) -> {
          String raw = declared(TRANSPORT_PREFIX + key);
          assertEquals(
              javaDefault,
              resolver.resolvePlaceholders(raw).toString(),
              "shipped default of " + key);
          assertOverride(TRANSPORT_PREFIX + key, envName("PLOWSHARE_TRANSPORT_", key), javaDefault);
        });
  }

  @Test
  void every_projection_cache_key_is_declared_in_application_yml() {
    assertRecordMatchesFile("projection-cache", ProjectionCacheProperties.class);
    var fromFile =
        shippedBinder()
            .bindOrCreate(
                "plowshare.projection-cache", Bindable.of(ProjectionCacheProperties.class));
    var javaDefaults =
        emptyBinder()
            .bindOrCreate(
                "plowshare.projection-cache", Bindable.of(ProjectionCacheProperties.class));
    assertEquals(javaDefaults, fromFile);
  }

  @Test
  void every_keepalive_key_is_declared_in_application_yml() throws Exception {
    // The PLOWSHARE_WS_KEEPALIVE_* names are legacy and deliberately not renamed.
    assertRecordMatchesFile(
        "transport.keepalive", "PLOWSHARE_WS_KEEPALIVE_", WebSocketKeepAliveProperties.class);
    // Bound values, not strings: Java writes 30s, the file writes PT30S; both are one Duration.
    var fromFile =
        shippedBinder()
            .bindOrCreate(
                "plowshare.transport.keepalive", Bindable.of(WebSocketKeepAliveProperties.class));
    var javaDefaults =
        emptyBinder()
            .bindOrCreate(
                "plowshare.transport.keepalive", Bindable.of(WebSocketKeepAliveProperties.class));
    assertEquals(javaDefaults, fromFile);
    // ScheduledWebSocketKeepAlive repeats the check-interval default in its own @Scheduled
    // placeholder; it must agree with the record or the timer silently diverges from it.
    Scheduled scheduled =
        // The class is package-private, so it is reached by name; a rename fails here loudly.
        Class.forName(
                WebSocketKeepAliveProperties.class.getPackageName()
                    + ".ScheduledWebSocketKeepAlive")
            .getDeclaredMethod("check")
            .getAnnotation(Scheduled.class);
    Matcher m = PLACEHOLDER.matcher(scheduled.fixedDelayString());
    assertTrue(
        m.matches() && m.group(1).equals("plowshare.transport.keepalive.check-interval"),
        "unparseable keepalive placeholder: " + scheduled.fixedDelayString());
    assertEquals(javaDefaults.checkInterval(), Duration.parse(m.group(2)));
  }

  private void assertOverride(String property, String env, String defaultValue) {
    String raw = declared(property);
    String prefix = "${" + env + ":";
    assertTrue(
        raw.startsWith(prefix) && raw.endsWith("}"),
        property + " must be declared as " + prefix + "default} but is " + raw);
    if (defaultValue != null) {
      assertEquals(prefix + defaultValue + "}", raw);
    }
  }
}
