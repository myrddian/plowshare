package io.aeyer.plowshare.integrations.ha;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The same runtime dependency closure shipped by installDist, without server test dependencies. */
class HomeAssistantInstalledRuntimeTest {
  @TempDir Path directory;

  @Test
  void owningRuntimePersistsAndReadsNonemptyOptionalCapabilityMetadata() throws Exception {
    var result = runConsumer(false);
    assertEquals(0, result.exitCode(), result.output());
    assertTrue(result.output().contains("PASS_CAPABILITY_RUNTIME"));
  }

  @Test
  void missingOptionalModuleReproducesInstalledRuntimeFailure() throws Exception {
    var result = runConsumer(true);
    assertEquals(1, result.exitCode(), result.output());
    assertTrue(result.output().contains("FAILED:InvalidDefinitionException"), result.output());
    assertFalse(result.output().contains("PASS_CAPABILITY_RUNTIME"));
  }

  private record Result(int exitCode, String output) {}

  private Result runConsumer(boolean omitOptionalModule) throws Exception {
    directory = directory.toRealPath();
    String runtime = Objects.requireNonNull(System.getProperty("integration.worker.classpath"));
    List<String> entries = Arrays.asList(runtime.split(Pattern.quote(File.pathSeparator)));
    assertTrue(entries.stream().anyMatch(value -> value.contains("jackson-datatype-jdk8")));
    assertFalse(
        entries.stream()
            .anyMatch(
                value ->
                    value.contains("plowshare-server")
                        || value.contains("junit")
                        || value.contains("test-classes")
                        || value.contains("/classes/java/test")));
    if (omitOptionalModule)
      runtime =
          entries.stream()
              .filter(value -> !value.contains("jackson-datatype-jdk8"))
              .collect(Collectors.joining(File.pathSeparator));
    Path consumer = Files.createDirectory(directory.resolve("consumer"));
    for (Class<?> type :
        List.of(
            HomeAssistantInstalledRuntimeConsumer.class,
            HomeAssistantInstalledRuntimeConsumer.RuntimeSource.class)) {
      String relative = type.getName().replace('.', '/') + ".class";
      Path target = consumer.resolve(relative);
      Files.createDirectories(target.getParent());
      try (var input = Objects.requireNonNull(type.getResourceAsStream("/" + relative))) {
        Files.copy(input, target);
      }
    }
    Path output = directory.resolve("runtime.log");
    var builder =
        new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp",
            runtime + File.pathSeparator + consumer,
            HomeAssistantInstalledRuntimeConsumer.class.getName(),
            directory.resolve("identities").toString());
    builder.directory(directory.toFile()).redirectErrorStream(true).redirectOutput(output.toFile());
    builder.environment().clear();
    Process child = builder.start();
    try {
      assertTrue(child.waitFor(15, TimeUnit.SECONDS), "Runtime consumer did not finish");
      assertTrue(Files.size(output) <= 32768, "Runtime consumer diagnostics exceed bound");
      return new Result(child.exitValue(), Files.readString(output, StandardCharsets.UTF_8));
    } finally {
      if (child.isAlive()) {
        child.destroyForcibly();
        if (!child.waitFor(3, TimeUnit.SECONDS))
          throw new IOException("Runtime consumer cleanup did not settle");
      }
    }
  }
}
