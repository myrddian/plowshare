package io.aeyer.plowshare.server.agents;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Several sources read as one, most specific first.
 *
 * <p><b>The first layer to name something wins it</b>, and the whole of the precedence argument is
 * which order a caller passes them in. Spec §2 fixes that order: the server's {@code
 * projects/<id>/}, then a connected client's {@code .plowshare/}, then {@code global/}, then the
 * shipped seed. The server's project copy beating the client's is the one place a more specific
 * source loses, and it is deliberate — the tree is the authority and a laptop must not silently
 * change what an operator configured.
 *
 * <p>Layering happens here rather than in {@code AgentRegistry.read} so that the loader keeps
 * having exactly one input and one reading. A definition shadowed here is never parsed, never
 * validated and never reported, which is correct: it is not part of this set.
 */
public final class LayeredDefinitions implements DefinitionSource {

  private final List<DefinitionSource> layers;

  public LayeredDefinitions(List<DefinitionSource> mostSpecificFirst) {
    this.layers = List.copyOf(Objects.requireNonNull(mostSpecificFirst, "mostSpecificFirst"));
  }

  @Override
  public String describe() {
    return layers.stream().map(DefinitionSource::describe).collect(Collectors.joining(", then "));
  }

  @Override
  public List<Definition> list() {
    Map<String, Definition> won = new LinkedHashMap<>();
    for (DefinitionSource layer : layers) {
      for (Definition candidate : layer.list()) {
        won.putIfAbsent(candidate.name(), candidate);
      }
    }
    List<Definition> all = new ArrayList<>(won.values());
    all.sort(Comparator.comparing(Definition::name));
    return List.copyOf(all);
  }
}
