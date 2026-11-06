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
    io.aeyer.plowshare.server.access.KnowledgeReadScope readBoundary,
    String attribution) {

  /**
   * {@link #account} under the name that says which of the two axes it is: <em>who is acting</em>.
   *
   * <p>Every authority gate is asked about this value — the destination gate, the resource lock,
   * readability — and for a contained run it is the child's own ephemeral principal, which is what
   * V229's write arm admits and what must never be widened to anything else.
   *
   * <p>An alias rather than a rename: {@code account()} is read at dozens of call sites and
   * renaming the component would have made a behavioural review of this change impossible to do by
   * reading the diff. New gate call sites should say {@code acting()}.
   */
  public String acting() {
    return account;
  }

  /**
   * The other axis: <em>who the work is accounted to</em>, which is what provenance columns record.
   *
   * <p>For every ordinary caller this is the acting principal, so nothing changes. It differs for
   * exactly one case, a run inside a contained Application (ADR 0042): the acting principal is the
   * child, and the accounting account is its container's, because a containment hop keeps the
   * parent's executor and account unchanged and a contained principal is deliberately not an {@code
   * authority_accounts} subject (V218).
   *
   * <p><strong>Nothing in production builds that case yet.</strong> {@code
   * InformationAccess.contained} is the only place the two axes are made to differ and it has no
   * production caller — a contained run's information actor is the container's executor — so every
   * caller this method serves today holds one value for both axes. See that method for why, and ADR
   * 0042 for the wiring it names as the next slice's prerequisite. The split is here so the gates
   * are decidable, not because the second axis is in use.
   *
   * <p><strong>Why the split had to exist here.</strong> One field served both axes, and no single
   * value can satisfy them: the child's principal passes V229's destination gate and then fails
   * every provenance column, each of which has a foreign key into {@code admins} or {@code
   * authority_accounts}; the container's account satisfies those keys and fails the gate, because a
   * contained project carries no {@code PROJECT} scope and so no role for anybody — not even for
   * the container. {@code ContainedExecutionWriteDatabaseTest} measures both halves.
   */
  public String attribution() {
    return attribution == null ? account : attribution;
  }

  /**
   * The same selection, accounted to another account: the contained case and nothing else.
   *
   * <p>It never widens what the caller may do — the acting principal, the selection and the read
   * boundary are all untouched, and every gate still asks about the acting principal. It only says
   * which account the rows this write leaves behind are attributed to.
   */
  public InformationContext accountedTo(String account) {
    return new InformationContext(
        this.account,
        selection,
        corpus,
        facets,
        typedView,
        readBoundary,
        Objects.requireNonNull(account, "account"));
  }

  public InformationContext(
      String account,
      Selection selection,
      Corpus corpus,
      InformationFacets facets,
      boolean typedView) {
    this(account, selection, corpus, facets, typedView, null, null);
  }

  public InformationContext within(io.aeyer.plowshare.server.access.KnowledgeReadScope boundary) {
    return new InformationContext(
        account,
        selection,
        corpus,
        facets,
        typedView,
        readBoundary == null ? Objects.requireNonNull(boundary) : readBoundary.intersect(boundary),
        attribution);
  }

  public InformationContext(
      String account, Selection selection, Corpus corpus, InformationFacets facets) {
    this(account, selection, corpus, facets, false);
  }

  public InformationContext withView(boolean supporting) {
    return new InformationContext(
        account, selection, corpus, facets, supporting, readBoundary, attribution);
  }

  public InformationContext(String account, Selection selection, Corpus corpus) {
    this(account, selection, corpus, InformationFacets.NONE);
  }

  public InformationContext withFacets(InformationFacets value) {
    return new InformationContext(
        account,
        selection,
        corpus,
        Objects.requireNonNull(value),
        typedView,
        readBoundary,
        attribution);
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
        : new InformationContext(
            account, selection, value, facets, typedView, readBoundary, attribution);
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
