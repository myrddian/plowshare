package io.aeyer.plowshare.server.personal;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.DefinitionResolver;
import io.aeyer.plowshare.server.data.DataLayout;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.union.Hub;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One private union per account. Disk initialization is resumable; existing files are never
 * replaced.
 */
@Component
public final class PersonalSpaces implements ApplicationRunner {
  public static final List<String> SECTIONS =
      List.of("In", "Out", "Resources", "Archive", "Planning", "Bots");

  public record AccountCreated(String handle) {}

  private final JdbcTemplate jdbc;
  private final DataLayout data;
  private final TransactionTemplate transactions;

  public PersonalSpaces(JdbcTemplate jdbc, DataLayout data) {
    this.jdbc = jdbc;
    this.data = data;
    this.transactions =
        new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    if (data.keepsAnything()) data.usePersonalProjects(id -> owner(id).isPresent());
  }

  public static String name(String handle) {
    if (handle == null || handle.isBlank())
      throw new CallerFault("Personal space requires an authenticated account");
    return "personal:" + HexFormat.of().formatHex(handle.getBytes(StandardCharsets.UTF_8));
  }

  /** Public routing identity; the encoded storage name remains backwards compatible. */
  public static String address(String handle) {
    if (handle == null || handle.isBlank())
      throw new CallerFault("Personal space requires an authenticated account");
    return "Personal:" + handle;
  }

  public static String resolveAddress(String project, String handle) {
    if (project == null || !project.startsWith("Personal:")) return project;
    if (!project.equals(address(handle)))
      throw new CallerFault("This Personal project belongs to another account");
    return name(handle);
  }

  public static void requireOwn(String project, String handle) {
    if (project != null && project.startsWith("personal:") && !project.equals(name(handle))) {
      throw new CallerFault("This personal space belongs to another account");
    }
  }

  public Optional<String> owner(long id) {
    return jdbc
        .queryForList(
            "SELECT personal_owner FROM projects WHERE id = ? AND personal_owner IS NOT NULL",
            String.class,
            id)
        .stream()
        .findFirst();
  }

  public Optional<Long> id(String handle) {
    if (handle == null) return Optional.empty();
    return jdbc
        .queryForList("SELECT id FROM projects WHERE personal_owner = ?", Long.class, handle)
        .stream()
        .findFirst();
  }

  /** Physical policy files belong to this exact Personal identity, never the display label. */
  public Optional<Path> workspace(String project) {
    if (!data.keepsAnything()) return Optional.empty();
    return jdbc
        .queryForList(
            "SELECT id FROM projects WHERE name = ? AND personal_owner IS NOT NULL",
            Long.class,
            project)
        .stream()
        .findFirst()
        .map(id -> new Hub(data.unionFor(id)).tree());
  }

  public Home home(String requested, String handle) {
    requireOwn(requested, handle);
    if (requested != null) return io.aeyer.plowshare.server.requests.RequestedHome.in(requested);
    ensure(handle);
    return Home.of(name(handle));
  }

  @EventListener
  public void created(AccountCreated account) {
    ensure(account.handle());
  }

  @Override
  public void run(ApplicationArguments args) {
    for (String handle :
        jdbc.queryForList(
            "SELECT handle FROM admins WHERE NOT bootstrap AND account_kind='USER' ORDER BY handle",
            String.class)) ensure(handle);
  }

  public void ensure(String handle) {
    if (!jdbc.queryForList(
            "SELECT 1 FROM admins WHERE handle=? AND account_kind<>'USER'", Integer.class, handle)
        .isEmpty())
      throw new CallerFault("Service accounts have no Personal space; supply a project");
    String name = name(handle);
    if (!data.keepsAnything())
      throw new IllegalStateException("Personal spaces require PLOWSHARE_DATA_DIR");
    transactions.executeWithoutResult(
        status -> {
          jdbc.update(
              "INSERT INTO projects(name, personal_owner) VALUES (?, ?) ON CONFLICT (personal_owner) DO NOTHING",
              name,
              handle);
          long id =
              jdbc.queryForObject(
                  "SELECT id FROM projects WHERE personal_owner = ? FOR UPDATE",
                  Long.class,
                  handle);
          boolean initialized =
              Boolean.TRUE.equals(
                  jdbc.queryForObject(
                      "SELECT union_since IS NOT NULL FROM projects WHERE id = ?",
                      Boolean.class,
                      id));
          Hub hub = new Hub(data.unionFor(id));
          if (initialized
              && (!hub.exists() || hub.main().isEmpty() || !Files.isDirectory(hub.tree()))) {
            throw new IllegalStateException(
                "Personal space storage is missing for "
                    + name
                    + "; restore its union before starting");
          }
          if (!hub.exists()) hub.create();
          // Only an uninitialized union receives its initial skeleton. Reboots must not restore
          // deleted files.
          if (hub.main().isEmpty()) {
            try {
              for (String section : SECTIONS) {
                Path dir = hub.tree().resolve(section);
                Files.createDirectories(dir);
                if (!Files.exists(dir.resolve(".keep")))
                  Files.writeString(dir.resolve(".keep"), "");
              }
              Path config = hub.tree().resolve("Resources");
              for (String kind : List.of("agents", "skills", "orchestrations", "hooks")) {
                Path dir = config.resolve(kind);
                Files.createDirectories(dir);
                if (!Files.exists(dir.resolve(".keep")))
                  Files.writeString(dir.resolve(".keep"), "");
              }
              Path defaultBot = hub.tree().resolve("Bots/default");
              if (!Files.exists(defaultBot)) {
                try (var in =
                    DefinitionResolver.class.getClassLoader().getResourceAsStream("bots/default")) {
                  if (in != null) Files.copy(in, defaultBot);
                }
              }
              PersonalStarter.install(hub.tree());
            } catch (IOException failed) {
              throw new UncheckedIOException("Could not initialize personal space", failed);
            }
            hub.commitTree(Hub.SERVER_AUTHOR, "Create personal space");
          }
          jdbc.update(
              "UPDATE projects SET workspace = ?, union_since = COALESCE(union_since, now()) WHERE id = ?",
              "/personal",
              id);
          jdbc.update(
              "INSERT INTO project_members(project_id, handle,role) VALUES (?, ?, 'MANAGER') ON CONFLICT DO NOTHING",
              id,
              handle);
        });
  }
}
