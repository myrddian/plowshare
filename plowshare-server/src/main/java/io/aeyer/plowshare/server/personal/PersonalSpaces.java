package io.aeyer.plowshare.server.personal;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.DefinitionResolver;
import io.aeyer.plowshare.server.archive.UnitOfWork;
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
import org.springframework.stereotype.Component;

/**
 * One private union per account. Disk initialization is resumable; existing files are never
 * replaced.
 */
@Component
public final class PersonalSpaces implements ApplicationRunner, PersonalWorkspaces {
  public static final List<String> SECTIONS =
      List.of("In", "Out", "Resources", "Archive", "Planning", "Bots");

  public record AccountCreated(String handle) {}

  private final PersonalSpaceRepository repository;
  private final DataLayout data;
  private final UnitOfWork transactions;

  public PersonalSpaces(
      PersonalSpaceRepository repository, UnitOfWork transactions, DataLayout data) {
    this.repository = repository;
    this.data = data;
    this.transactions = transactions;
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
    return repository.owner(id);
  }

  public Optional<Long> id(String handle) {
    return repository.id(handle);
  }

  /** Physical policy files belong to this exact Personal identity, never the display label. */
  public Optional<Path> workspace(String project) {
    if (!data.keepsAnything()) return Optional.empty();
    return repository.projectId(project).map(id -> new Hub(data.unionFor(id)).tree());
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
    for (String handle : repository.accounts()) ensure(handle);
  }

  public void ensure(String handle) {
    if (repository.serviceAccount(handle))
      throw new CallerFault("Service accounts have no Personal space; supply a project");
    String name = name(handle);
    if (!data.keepsAnything())
      throw new IllegalStateException("Personal spaces require PLOWSHARE_DATA_DIR");
    transactions.inTransaction(
        () -> {
          var reserved = repository.reserve(handle);
          long id = reserved.id();
          boolean initialized = reserved.initialized();
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
          repository.initialized(id, handle);
          return null;
        });
  }
}
