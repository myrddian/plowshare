package io.aeyer.plowshare.server.llm.accounting;

import java.util.HashSet;
import java.util.List;

/** An execution's immutable ancestry, nearest parent first; includes ancestors that made no call. */
public record UsageLineage(String id, List<String> ancestors) {

    public static final UsageLineage NONE = new UsageLineage(null, List.of());

    public UsageLineage {
        ancestors = List.copyOf(ancestors);
        if (id == null) {
            if (!ancestors.isEmpty()) {
                throw new IllegalArgumentException("ancestry needs a current identity");
            }
        } else {
            requireId(id);
            var seen = new HashSet<String>();
            seen.add(id);
            for (String ancestor : ancestors) {
                requireId(ancestor);
                if (!seen.add(ancestor)) {
                    throw new IllegalArgumentException("usage ancestry must be acyclic and unique");
                }
            }
        }
    }

    public static UsageLineage root(String id) {
        requireId(id);
        return new UsageLineage(id, List.of());
    }

    /** Extend the captured path rather than reconstructing a tree from inference records. */
    public UsageLineage child(String childId) {
        if (id == null) {
            throw new IllegalStateException("an absent lineage cannot have a child");
        }
        var path = new java.util.ArrayList<String>(ancestors.size() + 1);
        path.add(id);
        path.addAll(ancestors);
        return new UsageLineage(childId, path);
    }

    public String parentId() {
        return ancestors.isEmpty() ? null : ancestors.getFirst();
    }

    public String rootId() {
        return ancestors.isEmpty() ? id : ancestors.getLast();
    }

    static void requireId(String id) {
        if (id == null || id.isBlank() || id.length() > 256) {
            throw new IllegalArgumentException("usage identities need 1 to 256 nonblank characters");
        }
    }
}
