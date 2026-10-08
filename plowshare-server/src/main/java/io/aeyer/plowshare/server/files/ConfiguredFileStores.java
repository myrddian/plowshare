package io.aeyer.plowshare.server.files;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.FileStoreCatalog;
import io.aeyer.plowshare.protocol.FileStoreReference;
import io.aeyer.plowshare.server.archive.ApplicationPlacement;
import io.aeyer.plowshare.server.archive.ProjectRole;
import io.aeyer.plowshare.server.hooks.script.HookEngine;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.graalvm.polyglot.Source;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Operator-owned JavaScript registry. Evaluation cannot access the host and is cancelled after two
 * seconds. Exact source bytes are reread on each decision; unchanged definitions reuse a validated
 * snapshot. A bad replacement denies access instead of retaining stale grants.
 */
@Component
public final class ConfiguredFileStores implements FileStores, AutoCloseable {
  private static final ObjectMapper JSON =
      new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

  private record Store(Path root, Map<String, ProjectRole> accounts) {
    Store {
      accounts = Map.copyOf(accounts);
    }
  }

  private final Path file;
  private final HookEngine engine = new HookEngine();
  private final ScheduledExecutorService timer =
      Executors.newSingleThreadScheduledExecutor(
          Thread.ofPlatform().daemon().name("filestore-config-timeout").factory());
  private String cachedSource;
  private Map<String, Store> cached;

  public ConfiguredFileStores(@Value("${plowshare.filestores.config-file:}") String configured) {
    if (configured == null || configured.isBlank()) {
      file = null;
      return;
    }
    Path path = Path.of(configured);
    if (!path.isAbsolute() || configured.codePoints().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException(
          "plowshare.filestores.config-file must be an absolute path");
    file = path.normalize();
  }

  @Override
  public FileStoreCatalog catalog(String account) {
    if (account == null || account.isBlank())
      throw new IllegalArgumentException("An authenticated account is required");
    var visible =
        registry().entrySet().stream()
            .filter(entry -> entry.getValue().accounts().containsKey(account))
            .sorted(Map.Entry.comparingByKey())
            .map(
                entry ->
                    new FileStoreCatalog.Store(
                        entry.getKey(),
                        FileStoreCatalog.Role.valueOf(
                            entry.getValue().accounts().get(account).name())))
            .toList();
    return new FileStoreCatalog(visible);
  }

  @Override
  public Optional<Path> configurationFile() {
    return Optional.ofNullable(file);
  }

  private synchronized Map<String, Store> registry() {
    if (file == null)
      throw new WorkspaceRefusedException(
          "Configure this server's filestore.js before using FileStore references");
    try {
      if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file))
        throw new IOException("Registry must be a regular file");
      byte[] bytes;
      try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
        bytes = input.readNBytes(65537);
      }
      if (bytes.length > 65536) throw new IOException("Registry exceeds 64 KiB");
      String source = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
      if (source.equals(cachedSource)) return cached;
      Map<String, Store> parsed;
      try (var context = engine.newContext()) {
        var timeout = timer.schedule(() -> context.close(true), 2, TimeUnit.SECONDS);
        try {
          var exports =
              context.eval(
                  Source.newBuilder("js", source, "filestore.mjs")
                      .mimeType("application/javascript+module")
                      .build());
          if (!exports.hasMembers() || !exports.getMemberKeys().equals(Set.of("default")))
            throw new IllegalArgumentException("Export only the default FileStore definition");
          var stringify =
              context.eval(
                  "js",
                  """
              value => JSON.stringify(value, (_key, entry) => {
                if (typeof entry === 'undefined' || typeof entry === 'function'
                    || typeof entry === 'symbol' || typeof entry === 'bigint'
                    || (typeof entry === 'number' && !Number.isFinite(entry)))
                  throw new Error('FileStore definitions must contain JSON values');
                return entry;
              })
              """);
          var serialized = stringify.execute(exports.getMember("default"));
          if (!serialized.isString() || serialized.asString().length() > 65536)
            throw new IllegalArgumentException("Invalid FileStore definition");
          parsed = decode(JSON.readTree(serialized.asString()));
        } finally {
          timeout.cancel(false);
        }
      }
      cached = Map.copyOf(parsed);
      cachedSource = source;
      return cached;
    } catch (IOException | RuntimeException invalid) {
      throw new WorkspaceRefusedException(
          "Server FileStores are unavailable. Correct filestore.js and directory access, then retry.");
    }
  }

  private static Map<String, Store> decode(JsonNode body) {
    fields(body, Set.of("version", "defaultStore", "fileStores"));
    if (!body.path("version").isIntegralNumber()
        || !body.path("version").canConvertToInt()
        || body.path("version").intValue() != 1)
      throw new IllegalArgumentException("Use FileStore version 1");
    JsonNode stores = body.get("fileStores");
    if (stores == null || !stores.isObject() || stores.size() < 1 || stores.size() > 100)
      throw new IllegalArgumentException("Use between 1 and 100 FileStores");
    Map<String, Store> result = new HashMap<>();
    stores
        .fields()
        .forEachRemaining(
            entry -> {
              String alias = new FileStoreReference(entry.getKey(), "").store();
              JsonNode row = entry.getValue();
              fields(row, Set.of("root", "access"));
              JsonNode root = row.get("root");
              if (root == null
                  || !root.isTextual()
                  || root.textValue().length() > 4096
                  || root.textValue().codePoints().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("Invalid FileStore root");
              Path path = Path.of(root.textValue());
              if (!path.isAbsolute())
                throw new IllegalArgumentException("FileStore roots must be absolute");
              Map<String, ProjectRole> accounts = new HashMap<>();
              JsonNode access = row.get("access");
              if (access != null) {
                fields(access, Set.of("accounts"));
                JsonNode grants = access.get("accounts");
                if (grants == null || !grants.isArray() || grants.size() > 256)
                  throw new IllegalArgumentException("Invalid FileStore account grants");
                for (JsonNode grant : grants) {
                  fields(grant, Set.of("handle", "role"));
                  if (!grant.path("handle").isTextual() || !grant.path("role").isTextual())
                    throw new IllegalArgumentException("Invalid FileStore grant");
                  String handle = grant.get("handle").textValue();
                  if (handle.isBlank()
                      || !handle.equals(handle.strip())
                      || handle.length() > 512
                      || handle.codePoints().anyMatch(Character::isISOControl))
                    throw new IllegalArgumentException("Invalid FileStore account");
                  ProjectRole role = ProjectRole.valueOf(grant.get("role").textValue());
                  if (accounts.putIfAbsent(handle, role) != null)
                    throw new IllegalArgumentException("Duplicate FileStore account");
                }
              }
              result.put(alias, new Store(path.normalize(), accounts));
            });
    if (!body.path("defaultStore").isTextual()
        || !result.containsKey(body.get("defaultStore").textValue()))
      throw new IllegalArgumentException("defaultStore must name a FileStore");
    return result;
  }

  private static void fields(JsonNode value, Set<String> allowed) {
    if (value == null || !value.isObject())
      throw new IllegalArgumentException("Expected an object");
    value
        .fieldNames()
        .forEachRemaining(
            field -> {
              if (!allowed.contains(field))
                throw new IllegalArgumentException("Unknown FileStore field");
            });
  }

  private static Path location(Map<String, Store> registry, FileStoreReference reference) {
    Store store = registry.get(reference.store());
    if (store == null)
      throw new WorkspaceRefusedException("Unknown server FileStore alias: " + reference.store());
    try {
      if (!Files.isDirectory(store.root(), LinkOption.NOFOLLOW_LINKS)
          || Files.isSymbolicLink(store.root()))
        throw new IOException("FileStore directory unavailable");
      Path root = store.root().toRealPath(), at = root;
      for (Path segment : Path.of(reference.path())) {
        at = at.resolve(segment);
        if (Files.isSymbolicLink(at)) throw new IOException("Linked FileStore path");
        if (Files.exists(at, LinkOption.NOFOLLOW_LINKS) && !at.toRealPath().startsWith(root))
          throw new IOException("FileStore path escaped");
      }
      return at;
    } catch (IOException | SecurityException invalid) {
      throw new WorkspaceRefusedException(
          "FileStore " + reference.store() + " is unavailable or its relative path uses a link");
    }
  }

  @Override
  public Placement resolve(ApplicationPlacement placement) {
    var registry = registry();
    return new Placement(
        location(registry, placement.applicationRoot()),
        placement.writableAreas().stream()
            .map(reference -> location(registry, reference))
            .toList());
  }

  @Override
  public boolean permits(FileStoreReference reference, String account, ProjectRole role) {
    var registry = registry();
    Store store = registry.get(reference.store());
    if (store == null || account == null) return false;
    ProjectRole granted = store.accounts().get(account);
    if (granted == null || !granted.allows(role)) return false;
    location(registry, reference);
    return true;
  }

  @Override
  @PreDestroy
  public void close() {
    timer.shutdownNow();
    engine.close();
  }
}
