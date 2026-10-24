package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.server.relay.Relay;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Owns the home-scope lookup and the revision row lock behind {@link InformationEventRecords}.
 *
 * <p>Three statements: two bounded to one revision, and a page-bounded read of the revision history
 * that supplies an owner its own events' detail -- the field that deliberately never enters a Relay
 * publication, because a publication is readable by the whole topic audience and outlives the
 * record it describes. None takes an installation-wide lock and none evaluates a readability
 * predicate per candidate account: the audience is the home scope's project, and who receives a
 * publication is decided by Relay subscriptions rather than by a recipient set computed here.
 */
public final class JdbcInformationEventRecords implements InformationEventRecords {
  private final JdbcTemplate jdbc;

  public JdbcInformationEventRecords(JdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  public Audience resolve(UUID revision) {
    Objects.requireNonNull(revision, "revision");

    // The event's ordering used to be a global advisory fence taken right after this; Relay's own
    // per-topic row lock replaces it. This row lock stays, and is taken first for the reason its
    // original comment records: a worker can already hold FOR UPDATE on this revision while
    // publishing its own event, so waiting for that worker while holding anything wider deadlocks.
    // Transactions publishing several revisions must resolve all of them before the first append.
    jdbc.queryForObject(
        "SELECT id FROM information_revision_allowances WHERE id=? FOR KEY SHARE",
        UUID.class,
        revision);

    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT s.kind AS kind,s.builtin AS builtin,s.project_id AS project_id,"
                + "r.document_type AS document_type,"
                + "r.document_subtype AS document_subtype,q.owner_handle AS owner,"
                + "personal.id AS personal_project"
                + " FROM information_revision_allowances r"
                + " JOIN information_resources q ON q.id=r.resource_id"
                + " JOIN authority_scopes s ON s.id=q.home_scope_id"
                + " LEFT JOIN authority_accounts a ON a.id=s.account_id"
                + " LEFT JOIN projects personal ON personal.personal_owner=a.legacy_handle"
                + " WHERE r.id=?",
            revision);

    String kind = (String) row.get("kind");
    String builtin = (String) row.get("builtin");
    Long project =
        audienceProject(
            kind, builtin, number(row.get("project_id")), number(row.get("personal_project")));
    if (project == null)
      throw new Unaddressable(
          "no Relay audience exists for an information event homed in "
              + (builtin == null ? kind : builtin)
              + " (revision "
              + revision
              + "); see decision 0039");

    return new Audience(
        new Relay.ProjectScope(project),
        (String) row.get("document_type"),
        (String) row.get("document_subtype"),
        (String) row.get("owner"));
  }

  @Override
  public void append(Entry entry) {
    Objects.requireNonNull(entry, "entry");
    if (!(entry.scope() instanceof Relay.ProjectScope project))
      throw new IllegalStateException(
          "an information event history entry needs a project audience");
    // One statement, in the publishing transaction, so history and publication commit together or
    // not at all. No lock is taken: the key is this revision's own topic position, which Relay has
    // already allocated under its topic lock, so two concurrent publishers cannot collide here.
    jdbc.update(
        "INSERT INTO information_revision_events"
            + "(revision_id,relay_project,relay_position,generation,actor_handle,stage,action,detail,recorded_at)"
            + " VALUES(?,?,?,?,?,?,?,?,?)",
        entry.revision(),
        project.projectId(),
        entry.position(),
        entry.generation(),
        entry.actor(),
        entry.stage(),
        entry.action(),
        entry.detail() == null ? "" : entry.detail(),
        entry.recordedAt().atOffset(java.time.ZoneOffset.UTC));
  }

  @Override
  public Map<Long, String> details(Relay.Scope scope, java.util.Set<Long> positions) {
    Objects.requireNonNull(positions, "positions");
    if (!(scope instanceof Relay.ProjectScope project))
      throw new IllegalStateException("an information event history read needs a project audience");
    if (positions.size() > 100)
      throw new IllegalArgumentException("an information event detail read is bounded to one page");
    if (positions.isEmpty()) return Map.of();

    // One statement for the whole page rather than one per event. The placeholder count is derived
    // from the bounded set size above, so nothing from a request is concatenated into the SQL; the
    // positions themselves are bound.
    var arguments = new java.util.ArrayList<Object>(positions.size() + 1);
    arguments.add(project.projectId());
    arguments.addAll(positions);
    String placeholders = String.join(",", java.util.Collections.nCopies(positions.size(), "?"));

    var found = new java.util.HashMap<Long, String>();
    jdbc.query(
        "SELECT relay_position,detail FROM information_revision_events"
            + " WHERE relay_project=? AND relay_position IN ("
            + placeholders
            + ")",
        row -> {
          // A row recorded without detail stores the empty string, which is the same answer as no
          // row at all, so it is left out rather than returned as a present empty value.
          String detail = row.getString("detail");
          if (detail != null && !detail.isEmpty()) found.put(row.getLong("relay_position"), detail);
        },
        arguments.toArray());
    return Map.copyOf(found);
  }

  /**
   * The project whose Relay topic a home scope maps to, or {@code null} when none exists.
   *
   * <p>Separated from the query on purpose, so the mapping can be decided — and disagreed with —
   * for combinations the schema will not currently produce. {@code builtin} being checked before a
   * project is used is the load-bearing conjunct: GLOBAL_KNOWLEDGE shares {@code
   * ACCOUNT_KNOWLEDGE}'s kind, and today it also happens to have no personal project, so dropping
   * that conjunct would route global-read content to a real audience the moment either fact changed
   * while every database fixture kept passing. {@code InformationEventAudienceMappingTest} supplies
   * the combination the database cannot.
   *
   * <p>Only PROJECT and PERSONAL use {@code project}. RESTRICTED has one too and must never reach
   * an audience through it; ADMINISTRATION and any kind this does not recognise are refused the
   * same way, because an unrecognised home is not a reason to guess.
   */
  static Long audienceProject(String kind, String builtin, Long project, Long personalProject) {
    if (builtin != null) return null;
    return switch (kind == null ? "" : kind) {
      // A personal space is a project, so PERSONAL needs no alias of its own.
      case "PROJECT", "PERSONAL" -> project;
      // V185: an account's knowledge scope and its personal project are the same place, so the
      // knowledge scope's audience is that project's.
      case "ACCOUNT_KNOWLEDGE" -> personalProject;
      default -> null;
    };
  }

  private static Long number(Object value) {
    return value instanceof Number found ? found.longValue() : null;
  }
}
