package io.aeyer.plowshare.server.agents;

import java.util.List;

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

  /** Every definition here, in a stable order. Empty rather than null. */
  List<Definition> list();

  /**
   * One definition, read.
   *
   * @param name the stem, which {@code AgentRegistry} requires to equal the {@code name} in the
   *     frontmatter
   * @param origin what a refusal about this definition should name
   * @param text the whole file, frontmatter and body
   */
  record Definition(String name, String origin, String text) {}
}
