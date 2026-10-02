package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.List;
import java.util.Objects;

/**
 * Authenticated selection admission and parameterized, live permission checks over every input revision.
 */
public final class InformationAccess {
    private final ProjectMembers members;

    public InformationAccess(ProjectMembers members) {
        this.members = Objects.requireNonNull(members, "members");
    }

    public InformationContext resolve(String account, InformationContext.Selection selection) {
        if (account == null || account.isBlank()) {
            throw new CallerFault("information needs a socket or run authenticated as an account");
        }
        InformationContext context = new InformationContext(account,
                selection == null ? InformationContext.Selection.personal() : selection);
        requireSelection(context);
        return context;
    }

    public InformationContext forRun(String account, io.aeyer.plowshare.protocol.Home home) {
        return resolve(account, home.isGlobal() ? InformationContext.Selection.personal()
                : InformationContext.Selection.project(home.project()));
    }

    /** Check every operation again; an earlier context is not a membership lease. */
    public void requireSelection(InformationContext context) {
        Objects.requireNonNull(context, "context");
        if (context.selection().scope() == InformationContext.Scope.PROJECT
                && !members.isMember(context.selection().project(), context.account())) {
            throw new CallerFault("this account may not read the selected project's information");
        }
    }

    /**
     * Apply in WHERE before ordering, LIMIT, totals or coverage. Membership is also checked in SQL
     * so a removal between admission and the query cannot reveal project material.
     */
    public ReadFilter filter(InformationContext context, String documentAlias) {
        requireSelection(context);
        if (documentAlias == null || !documentAlias.matches("[a-z][a-z0-9_]*")
                || List.of("ip", "p", "m").contains(documentAlias)) {
            throw new IllegalArgumentException("document alias must be a simple internal SQL identifier");
        }
        return new ReadFilter("information_readable(" + documentAlias + ".id, ?, ?, ?, ?)",
                java.util.Collections.unmodifiableList(java.util.Arrays.asList(context.account(),
                        context.selection().scope().name().toLowerCase(java.util.Locale.ROOT),
                        context.selection().project(), context.selection().includeShared())));
    }

    public record ReadFilter(String sql, List<Object> arguments) {
        public ReadFilter {
            Objects.requireNonNull(sql, "sql");
            arguments = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(arguments));
        }
    }
}
