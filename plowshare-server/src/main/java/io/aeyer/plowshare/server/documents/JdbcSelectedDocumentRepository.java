package io.aeyer.plowshare.server.documents;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.aeyer.plowshare.server.api.CitationsResponse;
import io.aeyer.plowshare.server.api.DocumentDetailResponse;
import io.aeyer.plowshare.server.faults.NotFoundFault;
import io.aeyer.plowshare.server.information.InformationReadSelection;
import io.aeyer.plowshare.server.information.InformationSelectedSql;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Source admission and audit inserts share a statement; provider IO never holds this repository.
 */
public final class JdbcSelectedDocumentRepository implements SelectedDocumentRepository {
  private static final JsonMapper JSON =
      JsonMapper.builder().addModule(new JavaTimeModule()).build();
  private static final UUID NO_TARGET = new UUID(0, 0);
  private final JdbcTemplate jdbc;
  private final DocumentStore documents;

  public JdbcSelectedDocumentRepository(JdbcTemplate jdbc, DocumentStore documents) {
    this.jdbc = Objects.requireNonNull(jdbc);
    this.documents = Objects.requireNonNull(documents);
  }

  public void require(InformationReadSelection selection, UUID document) {
    var candidates =
        InformationSelectedSql.candidates(
            selection, List.of(document == null ? NO_TARGET : document));
    var args = new ArrayList<Object>(candidates.arguments());
    args.add(document == null ? 0 : 1);
    boolean allowed =
        Boolean.TRUE.equals(
            jdbc.queryForObject(
                "WITH selected AS MATERIALIZED ("
                    + candidates.sql()
                    + ") SELECT NOT EXISTS(SELECT 1 FROM selected WHERE NOT allowed) AND (SELECT count(*) FROM selected WHERE allowed)>=?",
                Boolean.class,
                args.toArray()));
    if (!allowed) throw unavailable();
  }

  public void retain(InformationReadSelection selection, List<UUID> revisions) {
    if (revisions.isEmpty()) {
      require(selection, null);
      return;
    }
    var targets = revisions.stream().distinct().toList();
    var candidates = InformationSelectedSql.candidates(selection, targets);
    var args = new ArrayList<Object>(candidates.arguments());
    args.add(targets.size());
    String sql =
        "WITH selected AS MATERIALIZED ("
            + candidates.sql()
            + "), checked AS MATERIALIZED (SELECT NOT EXISTS(SELECT 1 FROM selected WHERE NOT allowed) AND (SELECT count(*) FROM selected WHERE allowed)=? AS allowed)";
    if (selection.reader().audit() != null) {
      args.add(selection.reader().audit().attribution());
      args.add(selection.reader().audit().log());
      sql +=
          ", retained AS (INSERT INTO information_job_inputs(job_id,owner_handle,revision_id,scope,project_name,include_shared) SELECT parent.id,?,s.revision,s.read_scope,s.read_project,false FROM selected s CROSS JOIN checked c CROSS JOIN information_discovery_ancestors(?) parent WHERE c.allowed AND s.allowed ON CONFLICT DO NOTHING RETURNING revision_id)";
    }
    if (!Boolean.TRUE.equals(
        jdbc.queryForObject(sql + " SELECT allowed FROM checked", Boolean.class, args.toArray())))
      throw unavailable();
  }

  public DocumentDetailResponse outline(InformationReadSelection selection, UUID document) {
    require(selection, document);
    var view = documents.selected(selection, document);
    var source = Corpus.theOneToRead(view, document);
    var hierarchy = view.hierarchy(document);
    retain(selection, List.of(document));
    return DocumentDetailResponse.of(source, hierarchy);
  }

  private record Listed(boolean allowed, List<CitationsResponse.Cited> citations) {}

  public CitationsResponse citations(
      InformationReadSelection selection, UUID document, String conversation, int limit) {
    if (limit < 1 || limit > CitationStore.MOST_LISTED)
      throw new IllegalArgumentException("Invalid citation limit");
    // Conversation ownership is a separate authority axis from project/source READ, matching
    // scoped CitationStore. An accessible source never exposes another account's transcript.
    var candidates = InformationSelectedSql.candidates(selection, List.of(NO_TARGET));
    var args = new ArrayList<Object>();
    args.add(selection.reader().actor());
    args.add(selection.reader().actor());
    args.add(selection.reader().boundaryHome());
    args.add(selection.reader().boundaryHome());
    args.add(selection.reader().boundaryHome());
    args.add(conversation);
    args.add(document);
    args.add(conversation);
    args.add(document);
    args.add(document);
    args.addAll(candidates.arguments().subList(0, 4));
    args.add(limit);
    String sql =
        """
        WITH blocked AS MATERIALIZED (SELECT log FROM information_unreadable_logs(?)),
        readable_logs AS MATERIALIZED (SELECT log.id FROM conversations log LEFT JOIN projects project ON project.id=log.project_id WHERE log.owner_handle=?
         AND log.id NOT IN (SELECT log FROM blocked)
         AND (?::text IS NULL OR project.name=? OR EXISTS(SELECT 1 FROM authority_scopes scope
          WHERE scope.project_id=log.project_id AND scope.kind='CONTAINED' AND contained_scope_in_home(scope.id,?)))),
        requested AS MATERIALIZED (SELECT ?::text AS conversation,?::uuid AS document),
        candidate_citations AS MATERIALIZED (
         SELECT c.* FROM citations c JOIN readable_logs log ON log.id=c.conversation_id
         WHERE (?::text IS NULL OR c.conversation_id=(SELECT conversation FROM requested))
          AND (?::uuid IS NULL OR c.document_id=?::uuid)),
        selected AS MATERIALIZED (SELECT * FROM information_selected_candidates(?,CAST(? AS uuid[]),?,?,
         ARRAY(SELECT DISTINCT document_id FROM candidate_citations WHERE document_id IS NOT NULL UNION SELECT document FROM requested WHERE document IS NOT NULL))),
        page AS MATERIALIZED (
         SELECT c.*,s.read_scope,s.read_project,d.title,p.text AS paragraph_text
         FROM candidate_citations c JOIN selected s ON s.revision=c.document_id AND s.allowed
         JOIN documents d ON d.id=c.document_id JOIN information_revisions r ON r.id=d.id AND r.text_hash=d.text_hash
         LEFT JOIN paragraphs p ON p.id=c.paragraph_id AND p.document_id=c.document_id
         ORDER BY c.cited_at DESC,c.id LIMIT ?)
        """;
    if (selection.reader().audit() != null) {
      var audit = selection.reader().audit();
      args.add(audit.attribution());
      args.add(audit.log());
      args.add(audit.attribution());
      args.add(audit.log());
      sql +=
          """
          , retained AS (INSERT INTO information_job_inputs(job_id,owner_handle,revision_id,scope,project_name,include_shared)
           SELECT parent.id,?,p.document_id,p.read_scope,p.read_project,false FROM page p
           CROSS JOIN information_discovery_ancestors(?) parent ON CONFLICT DO NOTHING RETURNING revision_id),
          inherited AS (INSERT INTO information_job_inputs(job_id,owner_handle,revision_id,scope,project_name,include_shared)
           SELECT DISTINCT parent.id,?,i.revision_id,i.scope,i.project_name,i.include_shared
           FROM page p CROSS JOIN LATERAL information_discovery_ancestors(p.conversation_id) source
           JOIN information_job_inputs i ON i.job_id=source.id
           CROSS JOIN information_discovery_ancestors(?) parent ON CONFLICT DO NOTHING RETURNING revision_id)
          """;
    }
    sql +=
        """
        SELECT jsonb_build_object('allowed',NOT EXISTS(SELECT 1 FROM selected WHERE NOT allowed)
         AND ((SELECT document FROM requested) IS NULL OR EXISTS(SELECT 1 FROM selected WHERE revision=(SELECT document FROM requested) AND allowed))
         AND ((SELECT conversation FROM requested) IS NULL OR EXISTS(SELECT 1 FROM readable_logs WHERE id=(SELECT conversation FROM requested))),
         'citations',coalesce((SELECT jsonb_agg(jsonb_build_object('id',id,
          'standing',CASE WHEN paragraph_id IS NULL THEN 'paragraph_gone' ELSE 'resolves' END,
          'paragraphId',paragraph_id,'documentId',document_id,'sourceName',source_name,'paragraphOrdinal',paragraph_ordinal,
          'title',title,'paragraphText',paragraph_text,'conversationId',conversation_id,'turnOrdinal',turn_ordinal,
          'agent',agent,'citedAt',cited_at) ORDER BY cited_at DESC,id) FROM page),'[]'::jsonb))::text
        """;
    try {
      var result =
          JSON.readValue(jdbc.queryForObject(sql, String.class, args.toArray()), Listed.class);
      if (!result.allowed()) throw unavailable();
      return new CitationsResponse(
          conversation != null ? "conversation" : document != null ? "document" : "scopes",
          limit,
          result.citations());
    } catch (IOException invalid) {
      throw new IllegalStateException("Invalid scoped citation projection", invalid);
    }
  }

  private static NotFoundFault unavailable() {
    return new NotFoundFault("Document or conversation is unavailable in the selected scopes");
  }
}
