package io.aeyer.plowshare.server.information;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.aeyer.plowshare.protocol.*;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Owns physical revision snapshots. Lock order is embedding slots, resource/revision, then source
 * projection rows. Gates/model calls occur outside this repository's transactions. Row identities
 * are remapped rather than shared, so ordinary revision deletion remains an atomic owned cleanup.
 */
public final class JdbcInformationProjectionRepository implements InformationProjectionRepository {
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .build();
  private final JdbcTemplate jdbc;
  private final InformationContentRepository content;

  public JdbcInformationProjectionRepository(
      JdbcTemplate jdbc, InformationContentRepository content) {
    this.jdbc = Objects.requireNonNull(jdbc);
    this.content = Objects.requireNonNull(content);
  }

  private record Header(UUID resource, long generation, String name, String kind) {}

  private Header lock(InformationContext context, UUID revision) {
    // Embedding publication/activation locks slots before corpus rows. Follow that order here.
    jdbc.query(
        "SELECT slot FROM embedding_slots ORDER BY slot FOR SHARE",
        (row, index) -> row.getString(1));
    content.requireWrite(context, revision);
    var rows =
        jdbc.query(
            "SELECT q.id,r.generation,q.source_name,q.kind FROM information_resources q JOIN information_revision_allowances r ON r.resource_id=q.id"
                + " WHERE r.id=? AND r.availability='active' AND r.document_type='document' AND r.source_bytes IS NOT NULL FOR UPDATE OF q,r",
            (row, index) ->
                new Header(
                    row.getObject(1, UUID.class),
                    row.getLong(2),
                    row.getString(3),
                    row.getString(4)),
            revision);
    if (rows.isEmpty())
      throw new CallerFault("Retained source is unavailable for metadata revision");
    var header = rows.getFirst();
    UUID head =
        jdbc.queryForObject(
            "SELECT id FROM information_revision_allowances WHERE resource_id=? ORDER BY ordinal DESC LIMIT 1",
            UUID.class,
            header.resource());
    if (!revision.equals(head)) throw new CallerFault("INFORMATION_REVISION_CONFLICT");
    jdbc.query(
        "SELECT stage FROM information_steps WHERE revision_id=? AND generation=? ORDER BY stage FOR UPDATE",
        (row, index) -> row.getString(1),
        revision,
        header.generation());
    jdbc.query(
        "SELECT id FROM documents WHERE id=? FOR SHARE",
        (row, index) -> row.getObject(1, UUID.class),
        revision);
    jdbc.query(
        "SELECT c.id FROM chunks c JOIN paragraphs p ON p.id=c.paragraph_id WHERE p.document_id=? ORDER BY c.id LIMIT 10001 FOR SHARE OF c",
        (row, index) -> row.getObject(1, UUID.class),
        revision);
    return header;
  }

  private String hashRows(String query, Object... arguments) {
    return jdbc.queryForObject(
        "SELECT encode(sha256(convert_to(coalesce(jsonb_agg(snapshot.content ORDER BY snapshot.content)::text,'[]'),'UTF8')),'hex') FROM ("
            + query
            + ") snapshot",
        String.class,
        arguments);
  }

  private String snapshotHash(UUID revision, long generation, List<String> reusable) {
    var hashes = new ArrayList<String>();
    hashes.add(
        hashRows(
            "SELECT jsonb_build_object('content',r.content_hash,'text',r.text_hash,'media',r.media_type,'title',r.title,'converter',r.converter,'outline',r.outline_top_level,"
                + "'extraction',encode(sha256(convert_to(coalesce(r.extracted_text,''),'UTF8')),'hex')) content FROM information_revision_allowances r WHERE r.id=?",
            revision));
    hashes.add(
        hashRows(
            "SELECT jsonb_build_object('stage',stage,'state',state,'fingerprint',fingerprint) content FROM information_steps WHERE revision_id=? AND generation=?",
            revision,
            generation));
    // Unreused outputs need no approval or copy identity. Bound retained corpus material before
    // aggregation; an oversized projection still permits a metadata revision with extraction only.
    if (reusable.contains("derive")) {
      hashes.add(hashRows("SELECT to_jsonb(d) content FROM documents d WHERE id=?", revision));
      hashes.add(
          hashRows("SELECT to_jsonb(c) content FROM chapters c WHERE document_id=?", revision));
      hashes.add(
          hashRows("SELECT to_jsonb(s) content FROM sections s WHERE document_id=?", revision));
      hashes.add(
          hashRows("SELECT to_jsonb(p) content FROM paragraphs p WHERE document_id=?", revision));
      hashes.add(
          hashRows(
              "SELECT to_jsonb(c) content FROM chunks c JOIN paragraphs p ON p.id=c.paragraph_id WHERE p.document_id=?",
              revision));
      hashes.add(
          hashRows(
              "SELECT to_jsonb(d) content FROM document_references d WHERE document_id=?",
              revision));
      hashes.add(
          hashRows(
              "SELECT to_jsonb(s) content FROM documents_embedding_staging s WHERE source_id=?",
              revision));
      hashes.add(
          hashRows(
              "SELECT to_jsonb(s) content FROM chunks_embedding_staging s JOIN chunks c ON c.id=s.source_id JOIN paragraphs p ON p.id=c.paragraph_id WHERE p.document_id=?",
              revision));
    }
    hashes.add(hashRows("SELECT to_jsonb(s) content FROM embedding_slots s"));
    hashes.add(
        hashRows(
            "SELECT to_jsonb(r) content FROM information_reports r WHERE revision_id=?", revision));
    hashes.add(
        hashRows(
            "SELECT to_jsonb(i) content FROM information_inputs i WHERE derived_revision=?",
            revision));
    hashes.add(
        hashRows(
            "SELECT to_jsonb(c) content FROM information_report_citations c WHERE report_revision=?",
            revision));
    return InformationCatalogue.sha256(String.join(":", hashes).getBytes(StandardCharsets.UTF_8));
  }

  private boolean complete(String stage, UUID revision) {
    return switch (stage) {
      case "extract" ->
          Boolean.TRUE.equals(
              jdbc.queryForObject(
                  "SELECT extracted_text IS NOT NULL AND text_hash IS NOT NULL FROM information_revision_allowances WHERE id=?",
                  Boolean.class,
                  revision));
      case "derive" -> boundedCorpus(revision);
      case "embed" -> vectorsComplete(revision, false);
      case "summarise" ->
          Boolean.TRUE.equals(
              jdbc.queryForObject(
                  "SELECT EXISTS(SELECT 1 FROM documents WHERE id=? AND summary IS NOT NULL)"
                      + " AND NOT EXISTS(SELECT 1 FROM paragraphs WHERE document_id=? AND summary IS NULL)"
                      + " AND NOT EXISTS(SELECT 1 FROM sections WHERE document_id=? AND summary IS NULL)"
                      + " AND NOT EXISTS(SELECT 1 FROM chapters WHERE document_id=? AND summary IS NULL)",
                  Boolean.class,
                  revision,
                  revision,
                  revision,
                  revision));
      case "summary_embed" -> vectorsComplete(revision, true);
      default -> false;
    };
  }

  /** Bound all material entering a reuse manifest; oversized outputs remain unprepared. */
  private boolean boundedCorpus(UUID revision) {
    Long paragraphs =
        jdbc.queryForObject(
            "SELECT count(*) FROM paragraphs WHERE document_id=?", Long.class, revision);
    if (paragraphs == null
        || paragraphs < 1
        || paragraphs > 10000
        || !Boolean.TRUE.equals(
            jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM documents WHERE id=?)", Boolean.class, revision)))
      return false;
    long bytes = 0;
    for (String table :
        List.of(
            "documents",
            "chapters",
            "sections",
            "paragraphs",
            "document_references",
            "chunks",
            "documents_embedding_staging",
            "chunks_embedding_staging")) {
      String selected =
          switch (table) {
            case "documents" -> "FROM documents material WHERE material.id=?";
            case "chunks" ->
                "FROM chunks material JOIN paragraphs p ON p.id=material.paragraph_id WHERE p.document_id=?";
            case "documents_embedding_staging" ->
                "FROM documents_embedding_staging material WHERE material.source_id=?";
            case "chunks_embedding_staging" ->
                "FROM chunks_embedding_staging material JOIN chunks c ON c.id=material.source_id JOIN paragraphs p ON p.id=c.paragraph_id WHERE p.document_id=?";
            default -> "FROM " + table + " material WHERE material.document_id=?";
          };
      Long count = jdbc.queryForObject("SELECT count(*) " + selected, Long.class, revision);
      if (count == null || count > (table.endsWith("_staging") ? 20000 : 10000)) return false;
      // Measure expanded snapshot bytes rather than compressed/TOAST storage sizes.
      bytes +=
          jdbc.queryForObject(
              "SELECT coalesce(sum(octet_length(to_jsonb(material)::text)),0) " + selected,
              Long.class,
              revision);
      if (bytes > 64L * 1024 * 1024) return false;
    }
    return true;
  }

  private boolean vectorsComplete(UUID revision, boolean summary) {
    boolean dual =
        Boolean.TRUE.equals(
            jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM embedding_slots WHERE active_space_id IS NOT NULL OR target_space_id IS NOT NULL)",
                Boolean.class));
    if (!dual)
      return Boolean.TRUE.equals(
          jdbc.queryForObject(
              summary
                  ? "SELECT EXISTS(SELECT 1 FROM documents WHERE id=? AND summary_embedding IS NOT NULL)"
                  : "SELECT EXISTS(SELECT 1 FROM chunks c JOIN paragraphs p ON p.id=c.paragraph_id WHERE p.document_id=?) AND NOT EXISTS(SELECT 1 FROM chunks c JOIN paragraphs p ON p.id=c.paragraph_id WHERE p.document_id=? AND c.embedding IS NULL)",
              Boolean.class,
              summary ? new Object[] {revision} : new Object[] {revision, revision}));
    String owners =
        summary
            ? "documents source WHERE source.id=? AND source.summary IS NOT NULL"
            : "chunks source JOIN paragraphs parent ON parent.id=source.paragraph_id WHERE parent.document_id=?";
    String staging = summary ? "documents_embedding_staging" : "chunks_embedding_staging";
    String missing =
        "SELECT 1 FROM "
            + owners
            + " AND EXISTS(SELECT 1 FROM embedding_slots slot"
            + " CROSS JOIN LATERAL (SELECT slot.active_space_id AS space,true AS materialized UNION ALL SELECT slot.target_space_id,false) target WHERE target.space IS NOT NULL"
            + " AND NOT ((slot.slot='code' AND source.code_space_id=target.space AND source.code_source_revision=source.embedding_source_revision AND source.code_embedding IS NOT NULL)"
            + " OR (slot.slot='prose' AND source.prose_space_id=target.space AND source.prose_source_revision=source.embedding_source_revision AND source.prose_embedding IS NOT NULL)"
            + " OR (NOT target.materialized AND EXISTS(SELECT 1 FROM "
            + staging
            + " staged WHERE staged.source_id=source.id AND staged.slot=slot.slot AND staged.space_id=target.space AND staged.source_revision=source.embedding_source_revision))))";
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM " + owners + ") AND NOT EXISTS(" + missing + ")",
            Boolean.class,
            revision,
            revision));
  }

  private Manifest capture(
      InformationContext context, UUID revision, Map<String, String> configuration) {
    var header = lock(context, revision);
    var stages =
        jdbc.query(
            "SELECT stage,state,fingerprint FROM information_steps WHERE revision_id=? AND generation=?",
            (row, index) ->
                new InformationProjectionReuse.Stage(
                    row.getString(1),
                    row.getString(2),
                    row.getString(3),
                    complete(row.getString(1), revision)),
            revision,
            header.generation());
    var reusable = InformationProjectionReuse.select(stages, configuration);
    return new Manifest(
        revision,
        header.resource(),
        header.generation(),
        snapshotHash(revision, header.generation(), reusable),
        configuration,
        reusable);
  }

  @Override
  public Prepared prepare(
      InformationContext context,
      UUID request,
      UUID revision,
      InformationContent.Binding binding,
      Map<String, String> configuration) {
    var retained =
        jdbc.query(
            "SELECT payload FROM information_reuse_preparations WHERE actor=? AND request_id=?",
            (row, index) -> {
              try {
                return JSON.readValue(row.getString(1), Prepared.class);
              } catch (java.io.IOException invalid) {
                throw new IllegalStateException("Invalid prepared projection manifest", invalid);
              }
            },
            context.account(),
            request);
    if (!retained.isEmpty()) {
      var previous = retained.getFirst();
      if (!previous.manifest().revision().equals(revision)
          || !Objects.equals(previous.binding(), binding))
        throw new CallerFault("INFORMATION_REVISION_CONFLICT");
      return previous;
    }
    var prepared = new Prepared(capture(context, revision, configuration), binding);
    jdbc.update(
        "INSERT INTO information_reuse_preparations(actor,request_id,payload) VALUES(?,?,CAST(? AS jsonb))",
        context.account(),
        request,
        InformationJson.json(prepared));
    return prepared;
  }

  private record Remap(UUID origin, UUID destination, UUID parent) {}

  private List<Remap> remap(String table, UUID revision, Map<UUID, UUID> parents) {
    if (!Set.of("chapters", "sections", "paragraphs", "chunks").contains(table))
      throw new IllegalArgumentException("Invalid copy table");
    String parent =
        switch (table) {
          case "sections" -> "chapter_id";
          case "paragraphs" -> "section_id";
          case "chunks" -> "paragraph_id";
          default -> "NULL::uuid";
        };
    String query =
        table.equals("chunks")
            ? "SELECT c.id,c.paragraph_id FROM chunks c JOIN paragraphs p ON p.id=c.paragraph_id WHERE p.document_id=?"
            : "SELECT id," + parent + " FROM " + table + " WHERE document_id=?";
    var result =
        jdbc.query(
            query,
            (row, index) -> {
              UUID previousParent = row.getObject(2, UUID.class);
              UUID targetParent = previousParent == null ? null : parents.get(previousParent);
              if (previousParent != null && targetParent == null)
                throw new IllegalStateException("Incomplete projection parent map");
              return new Remap(row.getObject(1, UUID.class), UUID.randomUUID(), targetParent);
            },
            revision);
    if (result.size() > 100000) throw new CallerFault("Projection copy exceeds row budget");
    return result;
  }

  private static Map<UUID, UUID> identities(List<Remap> remaps) {
    var result = new HashMap<UUID, UUID>();
    remaps.forEach(remap -> result.put(remap.origin(), remap.destination()));
    return result;
  }

  private static final String MAPPING =
      "jsonb_to_recordset(CAST(? AS jsonb)) AS map(origin UUID,destination UUID,parent UUID)";

  private void copyRows(UUID origin, UUID target, List<String> stages) {
    if (!stages.contains("derive")) return;
    boolean summaries = stages.contains("summarise");
    jdbc.update(
        "INSERT INTO documents(id,source_name,title,content_hash,text_hash,byte_size,ingested_at,ingested_by,top_level_label,information_namespace,document_type,document_subtype,summary)"
            + " SELECT ?,source_name,title,content_hash,text_hash,byte_size,ingested_at,ingested_by,top_level_label,?,document_type,document_subtype,"
            + (summaries ? "summary" : "NULL")
            + " FROM documents WHERE id=?",
        target,
        "revision:" + target,
        origin);
    var chapters = remap("chapters", origin, Map.of());
    var sections = remap("sections", origin, identities(chapters));
    var paragraphs = remap("paragraphs", origin, identities(sections));
    var chunks = remap("chunks", origin, identities(paragraphs));
    int copied =
        jdbc.update(
            "INSERT INTO chapters(id,document_id,ordinal,title,is_synthetic,summary) SELECT map.destination,?,old.ordinal,old.title,old.is_synthetic,"
                + (summaries ? "old.summary" : "NULL")
                + " FROM chapters old JOIN "
                + MAPPING
                + " ON old.id=map.origin WHERE old.document_id=?",
            target,
            InformationJson.json(chapters),
            origin);
    if (copied != chapters.size()) throw new IllegalStateException("Incomplete chapter copy");
    copied =
        jdbc.update(
            "INSERT INTO sections(id,chapter_id,document_id,ordinal,title,is_synthetic,summary) SELECT map.destination,map.parent,?,old.ordinal,old.title,old.is_synthetic,"
                + (summaries ? "old.summary" : "NULL")
                + " FROM sections old JOIN "
                + MAPPING
                + " ON old.id=map.origin WHERE old.document_id=?",
            target,
            InformationJson.json(sections),
            origin);
    if (copied != sections.size()) throw new IllegalStateException("Incomplete section copy");
    copied =
        jdbc.update(
            "INSERT INTO paragraphs(id,document_id,section_id,text,content_hash,occurrence,ordinal,summary) SELECT map.destination,?,map.parent,old.text,old.content_hash,old.occurrence,old.ordinal,"
                + (summaries ? "old.summary" : "NULL")
                + " FROM paragraphs old JOIN "
                + MAPPING
                + " ON old.id=map.origin WHERE old.document_id=?",
            target,
            InformationJson.json(paragraphs),
            origin);
    if (copied != paragraphs.size()) throw new IllegalStateException("Incomplete paragraph copy");
    copied =
        jdbc.update(
            "INSERT INTO chunks(id,paragraph_id,ordinal,text,byte_size,split_mid_sentence,embedding) SELECT map.destination,map.parent,old.ordinal,old.text,old.byte_size,old.split_mid_sentence,"
                + (stages.contains("embed") ? "old.embedding" : "NULL")
                + " FROM chunks old JOIN "
                + MAPPING
                + " ON old.id=map.origin",
            InformationJson.json(chunks));
    if (copied != chunks.size()) throw new IllegalStateException("Incomplete chunk copy");
    jdbc.update(
        "INSERT INTO document_references(id,document_id,ref_num,raw) SELECT gen_random_uuid(),?,ref_num,raw FROM document_references WHERE document_id=?",
        target,
        origin);
    jdbc.update(
        "INSERT INTO information_document_policies(document_id,owner_handle,visibility,project_id,assigned_at) SELECT ?,q.owner_handle,CASE WHEN q.project_id IS NULL THEN 'personal' ELSE 'project' END,q.project_id,now() FROM information_resources q JOIN information_revision_allowances r ON r.resource_id=q.id WHERE r.id=?",
        target,
        origin);
    if (stages.contains("embed")) copyVectors("chunks", chunks);
    if (stages.contains("summary_embed")) {
      jdbc.update(
          "UPDATE documents new SET summary_embedding=old.summary_embedding FROM documents old WHERE new.id=? AND old.id=?",
          target,
          origin);
      copyVectors("documents", List.of(new Remap(origin, target, null)));
    }
  }

  private void copyVectors(String table, List<Remap> remaps) {
    if (!Set.of("chunks", "documents").contains(table))
      throw new IllegalArgumentException("Invalid vector copy store");
    String mapping = InformationJson.json(remaps);
    jdbc.update(
        "UPDATE "
            + table
            + " new SET code_embedding=old.code_embedding,code_space_id=old.code_space_id,"
            + "code_source_revision=CASE WHEN old.code_embedding IS NULL THEN NULL ELSE new.embedding_source_revision END,"
            + "prose_embedding=old.prose_embedding,prose_space_id=old.prose_space_id,prose_source_revision=CASE WHEN old.prose_embedding IS NULL THEN NULL ELSE new.embedding_source_revision END"
            + " FROM "
            + table
            + " old JOIN "
            + MAPPING
            + " ON old.id=map.origin WHERE new.id=map.destination",
        mapping);
    jdbc.update(
        "INSERT INTO "
            + table
            + "_embedding_staging(source_id,slot,space_id,source_revision,embedding)"
            + " SELECT map.destination,staged.slot,staged.space_id,new.embedding_source_revision,staged.embedding FROM "
            + table
            + "_embedding_staging staged"
            + " JOIN "
            + table
            + " old ON old.id=staged.source_id JOIN "
            + MAPPING
            + " ON old.id=map.origin JOIN "
            + table
            + " new ON new.id=map.destination"
            + " WHERE staged.source_revision=old.embedding_source_revision AND staged.space_id IN (SELECT active_space_id FROM embedding_slots UNION SELECT target_space_id FROM embedding_slots)",
        mapping);
  }

  @Override
  public InformationCatalogue.Admission copy(
      InformationContext context,
      UUID request,
      Prepared prepared,
      Map<String, String> currentConfiguration) {
    var manifest = prepared.manifest();
    var current = capture(context, manifest.revision(), currentConfiguration);
    if (!current.equals(manifest)) throw new CallerFault("INFORMATION_REVISION_CONFLICT");
    UUID target = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO information_revisions(id,resource_id,ordinal,title,media_type,source_uri,content_hash,text_hash,byte_size,source_bytes,extracted_text,converter,outline_top_level,document_type,document_subtype,allowance_total,processing_policy,starting_revision,revised_by,created_by,creator_evidence)"
            + " SELECT ?,old.resource_id,(SELECT max(ordinal)+1 FROM information_revision_allowances WHERE resource_id=old.resource_id),old.title,old.media_type,old.source_uri,old.content_hash,"
            + (manifest.reusable().contains("extract") ? "old.text_hash" : "NULL")
            + ",old.byte_size,old.source_bytes,"
            + (manifest.reusable().contains("extract")
                ? "old.extracted_text,old.converter,old.outline_top_level"
                : "NULL,NULL,'[]'::jsonb")
            + ",old.document_type,old.document_subtype,0,'explicit_only',old.id,?,(SELECT id FROM authority_accounts WHERE legacy_handle=?),'authenticated_admission' FROM information_revision_allowances old WHERE old.id=?",
        target,
        context.account(),
        context.account(),
        manifest.revision());
    if (prepared.binding() != null) content.attach(target, prepared.binding());
    jdbc.update(
        "INSERT INTO information_inputs(derived_revision,input_revision) SELECT ?,input_revision FROM information_inputs WHERE derived_revision=?",
        target,
        manifest.revision());
    jdbc.update(
        "INSERT INTO information_reports(revision_id,feedback_revision,details,produced_by,definition_hash) SELECT ?,feedback_revision,details,produced_by,definition_hash FROM information_reports WHERE revision_id=?",
        target,
        manifest.revision());
    jdbc.update(
        "INSERT INTO information_report_citations(report_revision,evidence_id) SELECT ?,evidence_id FROM information_report_citations WHERE report_revision=?",
        target,
        manifest.revision());
    copyRows(manifest.revision(), target, manifest.reusable());
    for (String stage : InformationCatalogue.STAGES) {
      boolean ready = manifest.reusable().contains(stage);
      jdbc.update(
          "INSERT INTO information_steps(revision_id,generation,stage,state,fingerprint) VALUES(?,1,?,?,?)",
          target,
          stage,
          ready ? "ready" : "pending",
          ready ? manifest.configured().get(stage) : null);
      if (ready)
        jdbc.update(
            "INSERT INTO information_projection_reuse(revision_id,stage,origin_revision,origin_generation,fingerprint,manifest_hash) VALUES(?,?,?,?,?,?)",
            target,
            stage,
            manifest.revision(),
            manifest.generation(),
            manifest.configured().get(stage),
            manifest.snapshotHash());
    }
    String fingerprint =
        InformationCatalogue.sha256(
            InformationJson.json(
                    Arrays.asList(
                        "metadata-revision-v1",
                        manifest.revision(),
                        prepared.binding() == null ? null : prepared.binding().contract().version(),
                        prepared.binding() == null ? null : prepared.binding().metadataHash()))
                .getBytes(StandardCharsets.UTF_8));
    jdbc.update(
        "INSERT INTO information_requests(account,request_id,fingerprint,revision_id) VALUES(?,?,?,?)",
        context.account(),
        request,
        fingerprint,
        target);
    return new InformationCatalogue.Admission(target, manifest.resource(), true);
  }
}
