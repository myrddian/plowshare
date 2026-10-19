package io.aeyer.plowshare.server.information;

import java.util.Objects;

/**
 * Resource selection derived by an adapter from its authenticated caller, never model ownership.
 */
public record InformationContext(
    String account,
    Selection selection,
    Corpus corpus,
    InformationFacets facets,
    boolean typedView,
    io.aeyer.plowshare.server.access.KnowledgeReadScope readBoundary) {

  public InformationContext(
      String account,
      Selection selection,
      Corpus corpus,
      InformationFacets facets,
      boolean typedView) {
    this(account, selection, corpus, facets, typedView, null);
  }

  public InformationContext within(io.aeyer.plowshare.server.access.KnowledgeReadScope boundary) {
    return new InformationContext(
        account,
        selection,
        corpus,
        facets,
        typedView,
        readBoundary == null ? Objects.requireNonNull(boundary) : readBoundary.intersect(boundary));
  }

  public InformationContext(
      String account, Selection selection, Corpus corpus, InformationFacets facets) {
    this(account, selection, corpus, facets, false);
  }

  public InformationContext withView(boolean supporting) {
    return new InformationContext(account, selection, corpus, facets, supporting, readBoundary);
  }

  public InformationContext(String account, Selection selection, Corpus corpus) {
    this(account, selection, corpus, InformationFacets.NONE);
  }

  public InformationContext withFacets(InformationFacets value) {
    return new InformationContext(
        account, selection, corpus, Objects.requireNonNull(value), typedView, readBoundary);
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
    return value == null
        ? this
        : new InformationContext(account, selection, value, facets, typedView, readBoundary);
  }

  public InformationContext {
    if (account == null || account.isBlank()) {
      throw new IllegalArgumentException("information needs an authenticated account");
    }
    Objects.requireNonNull(selection, "selection");
    Objects.requireNonNull(corpus, "corpus");
    Objects.requireNonNull(facets, "facets");
  }

  /** Stable destination locator for receipts; authenticated identity is deliberately separate. */
  public String namespace() {
    return switch (selection.scope()) {
      case PROJECT -> "project:" + selection.project();
      case ACCOUNT -> "account:" + selection.account();
      case PERSONAL -> "account:" + account;
      case GLOBAL_READ ->
          throw new IllegalArgumentException("Global READ has no writable namespace");
    };
  }

  public enum Scope {
    PERSONAL,
    PROJECT,
    GLOBAL_READ,
    ACCOUNT
  }

  public record Selection(Scope scope, String project, boolean includeGlobal) {
    public Selection {
      Objects.requireNonNull(scope, "scope");
      if (scope == Scope.PROJECT) {
        if (project == null || project.isBlank()) {
          throw new IllegalArgumentException("project information selection needs a project");
        }
        io.aeyer.plowshare.protocol.Home.of(project);
      } else if (project != null) {
        throw new IllegalArgumentException("only project selection may name a project");
      }
      if ((scope == Scope.GLOBAL_READ || scope == Scope.ACCOUNT) && includeGlobal) {
        throw new IllegalArgumentException("general selection does not take includeGlobal");
      }
    }

    /** The only additional public account home; this does not grant access to that account. */
    public static Selection account(java.util.UUID id) {
      if (!io.aeyer.plowshare.server.access.BuiltInIdentities.GLOBAL.value().equals(id))
        throw new IllegalArgumentException("Unsupported account knowledge target");
      return new Selection(Scope.ACCOUNT, null, false);
    }

    @com.fasterxml.jackson.annotation.JsonProperty("account")
    @com.fasterxml.jackson.annotation.JsonInclude(
        com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public java.util.UUID account() {
      return scope == Scope.ACCOUNT
          ? io.aeyer.plowshare.server.access.BuiltInIdentities.GLOBAL.value()
          : null;
    }

    public static Selection personal() {
      return new Selection(Scope.PERSONAL, null, true);
    }

    public static Selection project(String project) {
      return new Selection(Scope.PROJECT, project, true);
    }

    public static Selection globalRead() {
      return new Selection(Scope.GLOBAL_READ, null, false);
    }
  }
}
