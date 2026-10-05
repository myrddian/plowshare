package io.aeyer.plowshare.server.information;

import java.util.Objects;

/**
 * Resource selection derived by an adapter from its authenticated caller, never model ownership.
 */
public record InformationContext(
    String account, Selection selection, Corpus corpus, InformationFacets facets) {

  public InformationContext(String account, Selection selection, Corpus corpus) {
    this(account, selection, corpus, InformationFacets.NONE);
  }

  public InformationContext withFacets(InformationFacets value) {
    return new InformationContext(account, selection, corpus, Objects.requireNonNull(value));
  }

  public InformationContext(String account, Selection selection) {
    this(account, selection, Corpus.DOCUMENTS);
  }

  public enum Corpus {
    DOCUMENTS("document"),
    CODE("code");
    private final String documentType;

    Corpus(String documentType) {
      this.documentType = documentType;
    }

    public String documentType() {
      return documentType;
    }
  }

  /** Corpus selection changes discovery, never ownership or workspace permissions. */
  public InformationContext withCorpus(Corpus value) {
    return value == null ? this : new InformationContext(account, selection, value, facets);
  }

  public InformationContext {
    if (account == null || account.isBlank()) {
      throw new IllegalArgumentException("information needs an authenticated account");
    }
    Objects.requireNonNull(selection, "selection");
    Objects.requireNonNull(corpus, "corpus");
    Objects.requireNonNull(facets, "facets");
  }

  public enum Scope {
    PERSONAL,
    PROJECT,
    SHARED
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
