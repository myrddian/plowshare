package io.aeyer.plowshare.server.information;

import java.util.Objects;

/** Resource selection derived by an adapter from its authenticated caller, never model ownership. */
public record InformationContext(String account, Selection selection) {

    public InformationContext {
        if (account == null || account.isBlank()) {
            throw new IllegalArgumentException("information needs an authenticated account");
        }
        Objects.requireNonNull(selection, "selection");
    }

    public enum Scope {
        PERSONAL, PROJECT, SHARED
    }

    public record Selection(Scope scope, String project, boolean includeShared) {
        public Selection {
            Objects.requireNonNull(scope, "scope");
            if (scope == Scope.PROJECT) {
                if (project == null || project.isBlank()) {
                    throw new IllegalArgumentException("project information selection needs a project");
                }
            } else if (project != null) {
                throw new IllegalArgumentException("only project selection may name a project");
            }
            if (scope == Scope.SHARED && includeShared) {
                throw new IllegalArgumentException("shared selection does not take includeShared");
            }
        }

        public static Selection personal() {
            return new Selection(Scope.PERSONAL, null, true);
        }

        public static Selection project(String project) {
            return new Selection(Scope.PROJECT, project, true);
        }

        public static Selection shared() {
            return new Selection(Scope.SHARED, null, false);
        }
    }
}
