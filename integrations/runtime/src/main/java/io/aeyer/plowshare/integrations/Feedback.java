package io.aeyer.plowshare.integrations;

import io.aeyer.plowshare.integrations.IntegrationContracts.*;
import java.util.*;

/** Context correlation only: an acknowledgment is not physical verification. */
final class Feedback {
  static final int MAX_PER_BINDING = 64, MAX_TOTAL = 256, MAX_DEPTH = 16;

  private Feedback() {}

  static String context(String value) {
    return value != null
            && !value.isBlank()
            && value.length() <= 128
            && value.equals(value.strip())
            && value.chars().noneMatch(Character::isISOControl)
        ? value
        : null;
  }

  static String key(String binding, String context) {
    return Json.identity(binding + ":" + context).toString();
  }

  record Cause(
      String binding,
      String fingerprint,
      String context,
      String operation,
      long expiresAt,
      boolean ambiguous,
      int depth,
      String parent) {
    Cause {
      Configuration.name(binding);
      IntegrationContracts.identity(fingerprint, 1024);
      IntegrationContracts.identity(context, 128);
      IntegrationContracts.identity(operation, 1024);
      if (depth < 0
          || depth > MAX_DEPTH
          || (depth > 0) != (parent != null)
          || parent != null && (Feedback.context(parent) == null || context.equals(parent)))
        throw new IllegalArgumentException("invalid causal lineage");
    }
  }

  record Correlation(Causality match, Cause descendant, String ambiguousKey) {}

  private record Link(String operation, String root, long expiresAt, int depth) {}

  /** Every retained parent must still lead to the same unambiguous acknowledged root. */
  private static Link resolve(
      Map<String, Cause> causes, Configuration.Binding binding, String context, long now) {
    if (context == null) return null;
    Cause first = causes.get(key(binding.name(), context));
    if (first == null) return null;
    int depth = first.depth();
    String operation = first.operation();
    long expires = first.expiresAt();
    if (expires <= now) return null;
    String cursor = context;
    for (int remaining = depth; remaining >= 0; remaining--) {
      Cause entry = causes.get(key(binding.name(), cursor));
      if (entry == null
          || !entry.binding().equals(binding.name())
          || !entry.fingerprint().equals(binding.fingerprint())
          || entry.ambiguous()
          || entry.expiresAt() != expires
          || !entry.operation().equals(operation)
          || entry.depth() != remaining) return null;
      if (remaining == 0) return new Link(operation, cursor, expires, depth);
      cursor = context(entry.parent());
      if (cursor == null) return null;
    }
    return null;
  }

  static Correlation correlate(
      Map<String, Cause> causes,
      Configuration.Binding b,
      io.aeyer.plowshare.protocol.IntegrationPayload.ActionContext ctx,
      long now) {
    Correlation absent = new Correlation(null, null, null);
    if (!b.feedback().suppressPipelineStarts() || ctx == null) return absent;
    String id = ctx == null ? null : context(ctx.id()),
        parent = ctx == null ? null : context(ctx.parent_id());
    String ownKey = id == null ? null : key(b.name(), id);
    Cause own = ownKey == null ? null : causes.get(ownKey);
    boolean retained = own != null && own.expiresAt() > now;
    Link exact = resolve(causes, b, id, now), ancestor = resolve(causes, b, parent, now);
    boolean conflict =
        id != null && id.equals(parent)
            || retained && own.depth() > 0 && parent != null && !parent.equals(own.parent())
            || exact != null
                && ancestor != null
                && (!exact.operation().equals(ancestor.operation())
                    || !exact.root().equals(ancestor.root())
                    || ancestor.depth() >= exact.depth());
    if (conflict) return new Correlation(null, null, retained ? ownKey : null);
    // An invalid/ambiguous retained identity must not escape through its parent.
    if (retained && exact == null) return absent;
    int limit = b.feedback().maxDepth();
    Link matched =
        exact != null && exact.depth() <= limit
            ? exact
            : ancestor != null && ancestor.depth() < limit ? ancestor : null;
    if (matched == null) return absent;
    boolean direct = matched == exact;
    int depth = direct ? matched.depth() : matched.depth() + 1;
    Cause descendant = null;
    if (!direct && id != null && !retained && limit > 1)
      descendant =
          new Cause(
              b.name(),
              b.fingerprint(),
              id,
              matched.operation(),
              matched.expiresAt(),
              false,
              depth,
              parent);
    return new Correlation(
        new Causality(
            matched.operation(),
            direct ? id : parent,
            direct ? "id" : "parent_id",
            matched.root(),
            depth,
            matched.expiresAt(),
            true),
        descendant,
        null);
  }
}
