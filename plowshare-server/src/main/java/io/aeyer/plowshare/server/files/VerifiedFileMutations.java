package io.aeyer.plowshare.server.files;

import io.aeyer.plowshare.protocol.FileRequest;
import io.aeyer.plowshare.protocol.FileResult;
import io.aeyer.plowshare.protocol.FileSource;
import io.aeyer.plowshare.protocol.Replacement;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Observes a single mutation on its original binding. Only classifications leave this object. */
public final class VerifiedFileMutations implements FileMutations {
  private enum Effect {
    CHANGED,
    UNCHANGED,
    UNKNOWN
  }

  private record Snapshot(FileSource source, boolean missing) {
    boolean matches(String expected) {
      return expected != null && source != null && expected.equals(source.sha256());
    }
  }

  private static final Snapshot UNKNOWN = new Snapshot(null, false);
  private static final Snapshot MISSING = new Snapshot(null, true);

  private final FileProvider provider;
  private final FileProvider observed;
  private final Consumer<FileMutationObservation> output;
  private String sent;
  private boolean replied;

  public VerifiedFileMutations(FileProvider provider, Consumer<FileMutationObservation> output) {
    this.provider = provider;
    this.output = output;
    this.observed =
        provider.observeMutation(
            observation -> {
              if (observation.kind() == FileMutationObservation.Kind.SENT) {
                sent = observation.requestId();
                output.accept(observation);
              } else if (observation.kind() == FileMutationObservation.Kind.CONFIRMED) {
                replied = Objects.equals(sent, observation.requestId());
              }
            });
  }

  @Override
  public Changed write(Path path, String content) {
    Snapshot before = probe(path);
    return change(
        FileRequest.WRITE,
        path,
        null,
        () -> observed.write(path, content),
        () -> contentEffect(before, path, hash(content)));
  }

  @Override
  public Changed create(Path path, String content) {
    // A matching create-only success proves the target was absent at creation.
    return change(
        FileRequest.WRITE,
        path,
        null,
        () -> observed.create(path, content),
        () -> contentEffect(MISSING, path, hash(content)));
  }

  @Override
  public Changed edit(Path path, String old, String replacement) {
    Snapshot before = probe(path);
    String expected = expectedEdit(path, before, old, replacement);
    return change(
        FileRequest.EDIT,
        path,
        null,
        () -> observed.edit(path, old, replacement),
        () -> contentEffect(before, path, expected));
  }

  @Override
  public Changed delete(Path path) {
    Snapshot before = probe(path);
    return change(
        FileRequest.DELETE,
        path,
        null,
        () -> observed.delete(path),
        () -> before.source() != null && probe(path).missing() ? Effect.CHANGED : Effect.UNKNOWN);
  }

  @Override
  public Changed move(Path from, Path to) {
    Snapshot before = probe(from);
    return change(
        FileRequest.MOVE,
        from,
        to,
        () -> observed.move(from, to),
        () ->
            before.source() != null
                    && probe(from).missing()
                    && probe(to).matches(before.source().sha256())
                ? Effect.CHANGED
                : Effect.UNKNOWN);
  }

  private Changed change(
      String op, Path path, Path to, Supplier<Changed> perform, Supplier<Effect> verify) {
    boolean local = LocalProvider.NAME.equals(provider.name());
    Changed result;
    try {
      output.accept(new FileMutationObservation(null, FileMutationObservation.Kind.ATTEMPTED));
      result = perform.get();
    } catch (FileRefusedException refused) {
      // These facts attest refusal before a write. I/O failures may leave partial effects.
      if ((local || sent != null)
          && matches(refused.facts(), op, path, null)
          && (FileResult.NO_MATCH.equals(refused.facts().kind())
              || FileResult.MANY_MATCHES.equals(refused.facts().kind())
              || FileResult.NO_FILE.equals(refused.facts().kind())
              || FileResult.NOT_TEXT.equals(refused.facts().kind())
              || (FileResult.REFUSED.equals(refused.facts().kind())
                  && (FileResult.EXISTS.equals(refused.facts().reason())
                      || FileResult.DESTINATION_EXISTS.equals(refused.facts().reason())))))
        publish(local, Effect.UNCHANGED);
      throw refused;
    }
    // A lost reply throws out of perform. In particular it never triggers a post-write read.
    if (local || replied) {
      Effect effect = matches(result.facts(), op, path, to) ? verify.get() : Effect.UNKNOWN;
      publish(local, effect);
    }
    return result;
  }

  private void publish(boolean local, Effect effect) {
    var kind =
        switch (effect) {
          case CHANGED ->
              local
                  ? FileMutationObservation.Kind.LOCAL_CHANGED
                  : FileMutationObservation.Kind.VERIFIED;
          case UNCHANGED ->
              local
                  ? FileMutationObservation.Kind.LOCAL_UNCHANGED
                  : FileMutationObservation.Kind.UNCHANGED;
          case UNKNOWN ->
              local
                  ? FileMutationObservation.Kind.LOCAL_UNVERIFIED
                  : FileMutationObservation.Kind.REPLY_UNVERIFIED;
        };
    output.accept(new FileMutationObservation(sent, kind));
  }

  private static boolean matches(FileResult facts, String op, Path path, Path to) {
    return facts != null
        && Objects.equals(facts.version(), FileResult.VERSION)
        && op.equals(facts.op())
        && path.toString().equals(facts.path())
        && Objects.equals(to == null ? null : to.toString(), facts.to());
  }

  private Effect contentEffect(Snapshot before, Path path, String expected) {
    if (expected == null || !probe(path).matches(expected)) return Effect.UNKNOWN;
    if (before.missing()) return Effect.CHANGED;
    if (before.source() == null) return Effect.UNKNOWN;
    return before.matches(expected) ? Effect.UNCHANGED : Effect.CHANGED;
  }

  private Snapshot probe(Path path) {
    try {
      return provider
          .mutationFingerprint(path)
          .map(source -> new Snapshot(source, false))
          .orElse(MISSING);
    } catch (WorkspaceRefusedException | WorkspaceUnavailableException unavailable) {
      return UNKNOWN;
    }
  }

  private String expectedEdit(Path path, Snapshot before, String old, String replacement) {
    if (before.source() == null) return null;
    try {
      String original =
          Replacement.decode(provider.snapshot(path, before.source(), FileSource.MAX_BYTES));
      return original == null ? null : hash(Replacement.edit(original, old, replacement).text());
    } catch (Replacement.Refused
        | WorkspaceRefusedException
        | WorkspaceUnavailableException unavailable) {
      return null;
    }
  }

  private static String hash(String value) {
    return FileContents.sha256(value.getBytes(StandardCharsets.UTF_8));
  }
}
