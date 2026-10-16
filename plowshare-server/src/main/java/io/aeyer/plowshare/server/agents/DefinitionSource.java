package io.aeyer.plowshare.server.agents;

import java.util.List;
import java.util.Objects;

/**
 * Where a set of agent definitions is read from, when "a directory" is not the answer.
 *
 * <p>{@code AgentsProperties} says the shipped definitions "are inside the jar, where nothing can
 * list them as a {@code Path}", and that is true. It is also not the obstacle it reads as: the
 * distinction is {@code Path} versus {@code Resource}, not listable versus unlistable. This
 * interface is that distinction paid for once, so the loader can read the classpath, a directory,
 * or a socket without knowing which it has.
 *
 * <p><b>{@link Definition#origin} is what a refusal message names</b>, and it is carried rather
 * than derived because the three implementations answer it differently: a path for a directory, a
 * phrase for the jar, and a client's own path plus its session for the channel. A message naming a
 * filesystem path that exists on no disk in the deployment is worse than the one it replaced.
 */
public interface DefinitionSource {

  /** This source, as an operator would name it in a log line. */
  String describe();

  /** Freeze one reading so validation, comparison and loading consume exactly the same bytes. */
  static DefinitionSource snapshot(DefinitionSource source) {
    var definitions = List.copyOf(source.list());
    var description = source.describe();
    return new DefinitionSource() {
      public String describe() {
        return description;
      }

      public List<Definition> list() {
        return definitions;
      }
    };
  }

  /** Every definition here, in a stable order. Empty rather than null. */
  List<Definition> list();

  /**
   * One definition, read.
   *
   * @param name the stem, which {@code AgentRegistry} requires to equal the {@code name} in the
   *     frontmatter
   * @param origin what a refusal about this definition should name
   * @param text the whole file, frontmatter and body
   * @param provenance assigned by the loader; an origin string is never evidence of trust
   */
  record Definition(String name, String origin, String text, Provenance provenance) {
    public Definition {
      Objects.requireNonNull(provenance, "provenance");
    }

    /** Authored files and channel content cannot claim installed-release provenance. */
    public Definition(String name, String origin, String text) {
      this(name, origin, text, Provenance.AUTHORED);
    }
  }

  /** Loader-supplied provenance, independent of paths, names and authored frontmatter. */
  enum Provenance {
    AUTHORED,
    BUNDLED
  }
}
