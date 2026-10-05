package io.aeyer.plowshare.server.files;

import io.aeyer.plowshare.protocol.CodeTrackingStatus;
import io.aeyer.plowshare.protocol.DocumentType;
import io.aeyer.plowshare.protocol.FileSource;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.documents.CodeOutline;
import io.aeyer.plowshare.server.documents.CodeProjection;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Run-local code navigation. Every lookup reconciles through the run's live file grants. */
public final class WorkspaceCodeMap {
  public static final int MAX_FILES = 1000,
      MAX_FILE_BYTES = 1024 * 1024,
      MAX_TOTAL_BYTES = 16 * 1024 * 1024;
  private final ProviderRouter router;
  private final BooleanSupplier cancelled;
  private boolean syntax = true;

  public WorkspaceCodeMap fingerprintsOnly() {
    syntax = false;
    return this;
  }

  private CodeMapObservations observations = CodeMapObservations.NONE;

  public WorkspaceCodeMap observing(CodeMapObservations observations) {
    this.observations = Objects.requireNonNull(observations);
    return this;
  }

  public CodeTrackingStatus trackingStatus(Home home) {
    return observations.status(home);
  }

  public void stopTracking(Home home) {
    observations.stop(home);
  }

  private java.util.function.BiConsumer<Home, UUID> revisionReads = (home, revision) -> {};

  public WorkspaceCodeMap onRevisionRead(java.util.function.BiConsumer<Home, UUID> reads) {
    revisionReads = Objects.requireNonNull(reads);
    return this;
  }

  public void readRevision(Home home, UUID revision) {
    if (revision != null) revisionReads.accept(home, revision);
  }

  private Map<String, Entry> entries = Map.of();
  private String pattern = "**", state = "unscanned";
  private List<String> issues = List.of();
  private long generation;
  private int mutations;
  private boolean active;
  private Home home;
  private Instant checked;

  public record Entry(
      String key,
      String provider,
      Path root,
      Path path,
      FileSource fingerprint,
      String language,
      String text,
      CodeOutline outline,
      Instant observedAt,
      UUID revision) {
    public Entry(
        String key,
        String provider,
        Path root,
        Path path,
        FileSource fingerprint,
        String language,
        String text,
        CodeOutline outline,
        Instant observedAt) {
      this(key, provider, root, path, fingerprint, language, text, outline, observedAt, null);
    }
  }

  private record Provider(FileProvider files, List<Path> roots, String key) {}

  public record View(
      String state,
      long generation,
      String pattern,
      Instant checkedAt,
      List<String> issues,
      List<Entry> files,
      Map<String, Long> measurements) {
    public View(
        String state,
        long generation,
        String pattern,
        Instant checkedAt,
        List<String> issues,
        List<Entry> files) {
      this(state, generation, pattern, checkedAt, issues, files, Map.of());
    }
  }

  private Map<String, Long> measurements = Map.of();

  public WorkspaceCodeMap(ProviderRouter router, BooleanSupplier cancelled) {
    this.router = Objects.requireNonNull(router);
    this.cancelled = Objects.requireNonNull(cancelled);
  }

  /** Invalidate before a possibly mutating operation, including ambiguous command delivery. */
  public void beforeMutation() {
    beforeMutation(home);
  }

  public void beforeMutation(Home home) {
    synchronized (this) {
      generation++;
      mutations++;
      state = "dirty";
    }
    if (home != null) observations.invalidate(home);
  }

  public void afterMutation(Home home, List<Path> paths) {
    observations.completed(home);
    synchronized (this) {
      mutations = Math.max(0, mutations - 1);
      generation++;
      state = "dirty";
      if (!active || mutations > 0) return;
    }
    // Known paths are dropped immediately; a scan finds indirect additions,
    // renames and deletes too. Reconciliation never changes a command result.
    synchronized (this) {
      if (!paths.isEmpty()) {
        var kept = new LinkedHashMap<>(entries);
        kept.values().removeIf(entry -> paths.contains(entry.path()));
        entries = Map.copyOf(kept);
      }
    }
    try {
      reconcile(home, null);
    } catch (RuntimeException failed) {
      synchronized (this) {
        state = "partial";
        entries = Map.of();
        issues = List.of("reconciliation_failed");
      }
    }
  }

  public View reconcile(Home home, String requestedPattern) {
    Map<String, Entry> previous;
    long fence;
    String glob;
    synchronized (this) {
      active = true;
      if (!Objects.equals(this.home, home)
          || (requestedPattern != null && !pattern.equals(requestedPattern))) {
        entries = Map.of();
        generation++;
      }
      this.home = home;
      if (requestedPattern != null) pattern = requestedPattern;
      if (mutations > 0) return view();
      state = "checking";
      fence = ++generation;
      glob = pattern;
      previous = entries;
    }
    var ticket = observations.begin(home, glob);
    List<String> trouble = new ArrayList<>();
    Map<String, Entry> found = new LinkedHashMap<>();
    long started = System.nanoTime(), deadline = started + 30_000_000_000L, bytes = 0;
    long fingerprints = 0, snapshots = 0, hits = 0, parses = 0;
    List<Provider> providers = List.of();
    try {
      providers = providers(home);
      if (providers.isEmpty()) trouble.add("no_authorized_workspace");
      outer:
      for (Provider provider : providers) {
        List<Path> candidates;
        try {
          candidates = candidates(provider.files(), glob);
        } catch (WorkspaceRefusedException | WorkspaceUnavailableException failed) {
          trouble.add(
              provider.files().name()
                  + ": inventory_unavailable; narrow the files pattern if the provider's glob limit was reached");
          continue;
        }
        for (Path path : candidates) {
          if (cancelled.getAsBoolean()
              || Thread.currentThread().isInterrupted()
              || System.nanoTime() > deadline
              || found.size() >= MAX_FILES) {
            trouble.add("scan_limit_or_cancellation");
            break outer;
          }
          String key = provider.key() + "\n" + path;
          try {
            FileSource metadata = provider.files().fingerprint(path);
            fingerprints++;
            Instant observed = Instant.now();
            bytes += metadata.size();
            if (bytes > MAX_TOTAL_BYTES) {
              trouble.add("scan_byte_limit");
              break outer;
            }
            Entry old = previous.get(key);
            Path root =
                provider.roots().stream()
                    .filter(path::startsWith)
                    .max(Comparator.comparingInt(Path::getNameCount))
                    .orElseThrow(
                        () -> new WorkspaceRefusedException("file is outside advertised roots"));
            String language = DocumentType.classify(path.toString(), null).subtype();
            CodeProjection cached = null;
            if (syntax && metadata.size() <= MAX_FILE_BYTES) {
              try {
                cached = observations.cached(home, key, metadata);
              } catch (RuntimeException unavailable) {
                trouble.add("retained_index_unavailable");
              }
            }
            if (cached != null) {
              hits++;
              found.put(
                  key,
                  new Entry(
                      key,
                      provider.files().name(),
                      root,
                      path,
                      metadata,
                      language,
                      cached.text(),
                      cached.outline(),
                      observed,
                      cached.revision()));
            } else if (!syntax) {
              found.put(
                  key,
                  new Entry(
                      key,
                      provider.files().name(),
                      root,
                      path,
                      metadata,
                      language,
                      null,
                      new CodeOutline(
                          "not_requested",
                          "background_hash_scan",
                          language,
                          CodeOutline.VERSION,
                          metadata.sha256(),
                          List.of()),
                      observed));
            } else if (old != null
                && !observations.supportsIndexes()
                && old.revision() == null
                && old.fingerprint().equals(metadata)) {
              found.put(
                  key,
                  new Entry(
                      key,
                      provider.files().name(),
                      root,
                      path,
                      metadata,
                      language,
                      old.text(),
                      old.outline(),
                      observed));
            } else if (metadata.size() > MAX_FILE_BYTES) {
              found.put(
                  key,
                  new Entry(
                      key,
                      provider.files().name(),
                      root,
                      path,
                      metadata,
                      language,
                      null,
                      new CodeOutline(
                          "limited",
                          "source_size_limit",
                          language,
                          CodeOutline.VERSION,
                          metadata.sha256(),
                          List.of()),
                      observed));
            } else {
              byte[] source = provider.files().snapshot(path, metadata, MAX_FILE_BYTES);
              snapshots++;
              if (source.length != metadata.size()
                  || !FileContents.sha256(source).equals(metadata.sha256()))
                throw new WorkspaceUnavailableException(
                    "snapshot hash did not match source metadata");
              String text =
                  StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(source)).toString();
              if (text.indexOf('\0') >= 0)
                throw new IllegalArgumentException("source contains NUL");
              if (text.startsWith("\uFEFF")) text = text.substring(1);
              text = text.replace("\r\n", "\n").replace('\r', '\n');
              var pending =
                  new Entry(
                      key,
                      provider.files().name(),
                      root,
                      path,
                      metadata,
                      language,
                      text,
                      new CodeOutline(
                          "not_requested",
                          "retention_pending",
                          language,
                          CodeOutline.VERSION,
                          metadata.sha256(),
                          List.of()),
                      observed);
              CodeProjection retained = null;
              try {
                retained = observations.retain(home, pending, source);
              } catch (RuntimeException unavailable) {
                trouble.add("code_intake_unavailable");
              }
              if (retained != null)
                found.put(
                    key,
                    new Entry(
                        key,
                        pending.provider(),
                        root,
                        path,
                        metadata,
                        language,
                        retained.text(),
                        retained.outline(),
                        observed,
                        retained.revision()));
              else {
                parses++;
                found.put(
                    key,
                    new Entry(
                        key,
                        pending.provider(),
                        root,
                        path,
                        metadata,
                        language,
                        text,
                        CodeOutline.parse(text, language, path.toString()),
                        observed));
              }
            }
          } catch (WorkspaceRefusedException
              | WorkspaceUnavailableException
              | java.nio.charset.CharacterCodingException
              | IllegalArgumentException failed) {
            trouble.add(provider.files().name() + ": source_unavailable " + path);
          }
        }
        try {
          List<Path> verified = candidates(provider.files(), glob);
          if (!candidates.equals(verified)) {
            Set<Path> visible = new HashSet<>(verified);
            found
                .entrySet()
                .removeIf(
                    entry ->
                        entry.getKey().startsWith(provider.key() + "\n")
                            && !visible.contains(entry.getValue().path()));
            trouble.add(provider.files().name() + ": inventory_changed_during_scan");
          }
        } catch (WorkspaceRefusedException | WorkspaceUnavailableException failed) {
          found.entrySet().removeIf(entry -> entry.getKey().startsWith(provider.key() + "\n"));
          trouble.add(provider.files().name() + ": inventory_verification_failed");
        }
      }
      if (!providerKeys(providers).equals(providerKeys(providers(home)))) {
        found.clear();
        trouble.add("workspace_or_session_changed");
      }
    } catch (WorkspaceRefusedException | WorkspaceUnavailableException failed) {
      found.clear();
      trouble.add("workspace_unavailable");
    } catch (RuntimeException failed) {
      found.clear();
      trouble.add("scan_failed");
    }
    View result;
    synchronized (this) {
      if (generation != fence
          || mutations > 0
          || !Objects.equals(this.home, home)
          || !pattern.equals(glob)) {
        return view();
      } else {
        entries = Map.copyOf(found);
        state = trouble.isEmpty() ? "observed" : "partial";
        issues =
            trouble.stream()
                .limit(50)
                .map(issue -> issue.length() > 400 ? issue.substring(0, 400) : issue)
                .toList();
        checked = Instant.now();
      }
      measurements =
          Map.of(
              "fingerprints",
              fingerprints,
              "snapshot_reads",
              snapshots,
              "index_hits",
              hits,
              "live_parses",
              parses,
              "elapsed_ms",
              (System.nanoTime() - started) / 1_000_000);
      result = view();
    }
    observations.publish(ticket, result);
    return result;
  }

  /** Do not deliver an answer prepared before another tool invalidated this map. */
  public synchronized void verify(View view) {
    if (generation != view.generation()
        || mutations > 0
        || state.equals("dirty")
        || state.equals("checking"))
      throw new WorkspaceRefusedException(
          "workspace changed while preparing the code map; retry lookup");
  }

  public void verifyRead(Home home, Entry entry) {
    FileProvider provider = router.providerFor(home, entry.path());
    String key =
        provider.identity()
            + "\n"
            + provider.roots().stream().sorted().toList()
            + "\n"
            + entry.path();
    if (!entry.key().equals(key) || !entry.fingerprint().equals(provider.fingerprint(entry.path())))
      throw new WorkspaceRefusedException(
          "code source or workspace changed; refresh outline/symbols");
  }

  private List<Provider> providers(Home home) {
    List<Provider> found = new ArrayList<>();
    for (FileProvider provider : router.providersFor(home)) {
      List<Path> roots = provider.roots().stream().sorted().toList();
      if (!roots.isEmpty())
        found.add(new Provider(provider, roots, provider.identity() + "\n" + roots));
    }
    return found;
  }

  private static List<String> providerKeys(List<Provider> providers) {
    return providers.stream().map(Provider::key).sorted().toList();
  }

  private static List<Path> candidates(FileProvider provider, String glob) {
    return provider.glob(glob).stream()
        .filter(path -> DocumentType.classify(path.toString(), null).isCode())
        .map(Path::normalize)
        .distinct()
        .sorted()
        .toList();
  }

  private View view() {
    return new View(
        state,
        generation,
        pattern,
        checked,
        issues,
        state.equals("dirty") || state.equals("checking")
            ? List.of()
            : entries.values().stream().sorted(Comparator.comparing(Entry::key)).toList(),
        measurements);
  }
}
