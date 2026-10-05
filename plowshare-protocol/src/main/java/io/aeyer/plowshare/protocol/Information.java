package io.aeyer.plowshare.protocol;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.*;

/**
 * Immutable information catalogue views shared by server and SDK. Permission checks remain
 * server-owned.
 */
public final class Information {
  private Information() {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Revision(
      UUID id,
      @JsonProperty("resource_id") UUID resourceId,
      int ordinal,
      String title,
      @JsonProperty("media_type") String mediaType,
      @JsonProperty("document_type") String documentType,
      @JsonProperty("document_subtype") String documentSubtype,
      @JsonProperty("source_uri") String sourceUri,
      @JsonProperty("content_hash") String contentHash,
      @JsonProperty("text_hash") String textHash,
      @JsonProperty("byte_size") Long byteSize,
      @JsonProperty("created_at") @JsonFormat(shape = JsonFormat.Shape.STRING) Instant createdAt,
      String availability,
      boolean excluded,
      long generation,
      String converter,
      @JsonProperty("allowance_total") int allowanceTotal,
      @JsonProperty("allowance_spent") int allowanceSpent,
      @JsonProperty("source_name") String sourceName,
      String kind,
      String author,
      List<String> tags,
      List<String> autoTag,
      @JsonProperty("auto_tag_generated") Boolean autoTagGenerated,
      String documentAuthor,
      String documentAuthorSource,
      Map<String, List<String>> tagGroups,
      String tagGroupsSource,
      @JsonProperty("report_status") String reportStatus,
      @JsonProperty("can_manage") Boolean canManage,
      List<Step> steps,
      Progress progress,
      List<Event> events,
      List<UUID> inputs,
      Report report,
      List<UUID> citations) {
    public Revision {
      Objects.requireNonNull(id);
      Objects.requireNonNull(resourceId);
      Objects.requireNonNull(createdAt);
      if (ordinal < 1
          || generation < 1
          || allowanceTotal < 1
          || allowanceSpent < 0
          || allowanceSpent > allowanceTotal
          || byteSize != null && byteSize < 0)
        throw new IllegalArgumentException("invalid revision counters");
      sourceName = text(sourceName, 1024);
      title = text(title, 32768);
      if (!Set.of("source", "report").contains(kind)
          || !Set.of("code", "document").contains(documentType)
          || !Set.of("active", "excluded", "withdrawn", "deleted").contains(availability))
        throw new IllegalArgumentException("invalid revision kind or state");
      if (documentSubtype == null || !documentSubtype.matches("[a-zA-Z0-9_.+-]{1,128}"))
        throw new IllegalArgumentException("invalid document subtype");
      tags = strings(tags, 256, 256);
      if (autoTag != null) autoTag = strings(autoTag, 256, 256);
      if (tagGroups != null) {
        var copy = new LinkedHashMap<String, List<String>>();
        if (tagGroups.size() > 256) throw new IllegalArgumentException("too many tag groups");
        tagGroups.forEach((name, values) -> copy.put(text(name, 256), strings(values, 256, 256)));
        tagGroups = Map.copyOf(copy);
      }
      if (steps != null) steps = List.copyOf(steps);
      if (events != null) events = List.copyOf(events);
      if (inputs != null) inputs = List.copyOf(inputs);
      if (citations != null) citations = List.copyOf(citations);
    }

    public Revision withStatus(
        boolean readable,
        boolean manageable,
        List<Step> checkedSteps,
        Progress checkedProgress,
        List<Event> checkedEvents,
        List<UUID> checkedInputs,
        Report checkedReport,
        List<UUID> checkedCitations) {
      return new Revision(
          id,
          resourceId,
          ordinal,
          readable ? title : sourceName,
          mediaType,
          documentType,
          documentSubtype,
          readable ? sourceUri : null,
          contentHash,
          textHash,
          byteSize,
          createdAt,
          availability,
          excluded,
          generation,
          converter,
          allowanceTotal,
          allowanceSpent,
          sourceName,
          kind,
          author,
          tags,
          readable ? autoTag : null,
          readable ? autoTagGenerated : null,
          readable ? documentAuthor : null,
          readable ? documentAuthorSource : null,
          readable ? tagGroups : null,
          readable ? tagGroupsSource : null,
          reportStatus,
          manageable,
          checkedSteps,
          checkedProgress,
          checkedEvents,
          checkedInputs,
          checkedReport,
          checkedCitations);
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Step(
      String stage,
      String state,
      int attempt,
      String error,
      long generation,
      String fingerprint,
      @JsonProperty("started_at") @JsonFormat(shape = JsonFormat.Shape.STRING) Instant startedAt,
      @JsonProperty("finished_at") @JsonFormat(shape = JsonFormat.Shape.STRING) Instant finishedAt,
      Boolean compatible) {
    public Step {
      if (!Set.of(
                  "extract",
                  "derive",
                  "embed",
                  "summarise",
                  "summary_embed",
                  "autoTag",
                  "tagGroups")
              .contains(stage)
          || !Set.of("pending", "running", "ready", "skipped", "failed", "blocked", "cancelled")
              .contains(state)
          || attempt < 0
          || generation < 1) throw new IllegalArgumentException("invalid information step");
    }

    public Step visible(boolean readable, boolean owner, String expected) {
      return new Step(
          stage,
          state,
          attempt,
          readable && owner ? error : null,
          generation,
          readable ? fingerprint : null,
          readable ? startedAt : null,
          readable ? finishedAt : null,
          readable ? expected == null || expected.equals(fingerprint) : null);
    }
  }

  public record Completion(long total, long completed) {
    public Completion {
      if (total < 0 || completed < 0 || completed > total)
        throw new IllegalArgumentException("invalid progress");
    }
  }

  public record Progress(
      @JsonProperty("passage_vectors") Completion passageVectors,
      @JsonProperty("stored_summary_entities") Completion storedSummaryEntities) {
    public Progress {
      Objects.requireNonNull(passageVectors);
      Objects.requireNonNull(storedSummaryEntities);
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Event(
      long sequence,
      @JsonProperty("revision_id") UUID revisionId,
      long generation,
      String stage,
      String action,
      String detail,
      @JsonProperty("recorded_at") @JsonFormat(shape = JsonFormat.Shape.STRING) Instant recordedAt,
      @JsonProperty("document_type") String documentType,
      @JsonProperty("document_subtype") String documentSubtype) {
    public Event {
      if (sequence < 1 || generation < 1)
        throw new IllegalArgumentException("invalid event sequence");
      Objects.requireNonNull(recordedAt);
    }
  }

  public record Events(List<Event> events, long cursor) {
    public Events {
      events = List.copyOf(events);
      if (events.size() > 100 || cursor < 0)
        throw new IllegalArgumentException("invalid event page");
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Report(
      String status,
      @JsonProperty("feedback_revision") UUID feedbackRevision,
      @JsonProperty("finalised_at") @JsonFormat(shape = JsonFormat.Shape.STRING)
          Instant finalisedAt,
      InformationReportDetails details,
      @JsonProperty("produced_by") String producedBy,
      @JsonProperty("definition_hash") String definitionHash) {
    public Report {
      if (!Set.of("draft", "final", "superseded").contains(status))
        throw new IllegalArgumentException("invalid report state");
      Objects.requireNonNull(details);
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Window(
      UUID revision,
      Boolean matched,
      String reason,
      Integer start,
      Integer end,
      Integer total,
      String text,
      @JsonProperty("document_type") String documentType,
      @JsonProperty("document_subtype") String documentSubtype) {
    public Window {
      Objects.requireNonNull(revision);
      if (Boolean.FALSE.equals(matched)) {
        if (reason == null || start != null || end != null || text != null)
          throw new IllegalArgumentException("unmatched source cannot expose a location");
      } else if (start == null
          || end == null
          || start < 0
          || end < start
          || text == null
          || text.length() != end - start
          || total != null && total < end)
        throw new IllegalArgumentException("invalid retained text window");
    }

    public static Window unmatched(UUID revision, String reason) {
      return new Window(revision, false, reason, null, null, null, null, null, null);
    }
  }

  public record FacetCount(String value, long count) {
    public FacetCount {
      text(value, 32768);
      if (count < 0) throw new IllegalArgumentException("negative facet count");
    }
  }

  public record TagEdge(@JsonProperty("group") String category, String tag, long count) {
    public TagEdge {
      text(category, 256);
      text(tag, 256);
      if (count < 0) throw new IllegalArgumentException("negative edge count");
    }
  }

  public record TagGraph(List<TagEdge> edges, boolean hasMore) {
    public TagGraph {
      edges = List.copyOf(edges);
      if (edges.size() > 1000) throw new IllegalArgumentException("tag graph exceeds 1000 edges");
    }
  }

  public record Facets(
      long total,
      Map<String, List<FacetCount>> facets,
      Map<String, Boolean> hasMore,
      TagGraph tagGraph) {
    public Facets {
      if (total < 0) throw new IllegalArgumentException("negative facet total");
      var names =
          Set.of(
              "kind", "author", "documentAuthor", "tagGroup", "subtype", "when", "tags", "autoTag");
      if (!facets.keySet().equals(names) || !hasMore.keySet().equals(names))
        throw new IllegalArgumentException("incomplete facet schema");
      var copy = new LinkedHashMap<String, List<FacetCount>>();
      facets.forEach(
          (name, values) -> {
            if (values.size() > 100) throw new IllegalArgumentException("facet exceeds 100 values");
            copy.put(name, List.copyOf(values));
          });
      facets = Map.copyOf(copy);
      hasMore = Map.copyOf(hasMore);
      Objects.requireNonNull(tagGraph);
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Evidence(
      UUID id,
      @JsonProperty("revision_id") UUID revisionId,
      @JsonProperty("paragraph_id") UUID paragraphId,
      @JsonProperty("start_offset") int startOffset,
      @JsonProperty("end_offset") int endOffset,
      String quote,
      String locator,
      @JsonProperty("created_at") @JsonFormat(shape = JsonFormat.Shape.STRING) Instant createdAt) {
    public Evidence {
      Objects.requireNonNull(id);
      Objects.requireNonNull(revisionId);
      Objects.requireNonNull(createdAt);
      if (startOffset < 0
          || endOffset < startOffset
          || quote == null
          || quote.length() != endOffset - startOffset
          || quote.length() > 32768) throw new IllegalArgumentException("invalid evidence span");
      text(locator, 1024);
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Symbol(
      UUID revision,
      @JsonProperty("source_name") String sourceName,
      String language,
      @JsonProperty("outline_status") String outlineStatus,
      @JsonProperty("source_hash") String sourceHash,
      @JsonProperty("parser_version") String parserVersion,
      int ordinal,
      String name,
      String kind,
      @JsonProperty("qualified_name") String qualifiedName,
      @JsonProperty("parent_ordinal") Integer parentOrdinal,
      String signature,
      @JsonProperty("start_offset") int startOffset,
      @JsonProperty("end_offset") int endOffset,
      @JsonProperty("start_line") int startLine,
      @JsonProperty("end_line") int endLine) {
    public Symbol {
      if (ordinal < 0
          || parentOrdinal != null && parentOrdinal < 0
          || startOffset < 0
          || endOffset < startOffset
          || startLine < 1
          || endLine < startLine) throw new IllegalArgumentException("invalid syntax span");
      text(name, 32768);
      text(kind, 128);
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Outline(
      UUID revision,
      String language,
      String locator,
      @JsonProperty("source_kind") String sourceKind,
      String role,
      int offset,
      String status,
      String reason,
      @JsonProperty("source_hash") String sourceHash,
      @JsonProperty("parser_version") String parserVersion,
      @JsonProperty("symbol_count") int symbolCount,
      List<Symbol> symbols,
      @JsonProperty("has_more") boolean hasMore) {
    public Outline {
      Objects.requireNonNull(revision);
      symbols = List.copyOf(symbols);
      if (offset < 0 || symbolCount < 0 || symbols.size() > 200)
        throw new IllegalArgumentException("invalid outline page");
    }
  }

  public record Symbols(
      String query,
      int offset,
      @JsonProperty("has_more") boolean hasMore,
      List<Symbol> symbols,
      String locator,
      @JsonProperty("source_kind") String sourceKind,
      String role) {
    public Symbols {
      query = text(query, 128);
      symbols = List.copyOf(symbols);
      if (offset < 0 || symbols.size() > 200)
        throw new IllegalArgumentException("invalid symbol page");
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ReadinessOutcome(
      UUID revision,
      UUID acquisition,
      @JsonProperty("acquisition_state") String acquisitionState,
      @JsonProperty("extraction_state") String extractionState,
      String state,
      Integer attempt,
      String error,
      Long generation,
      @JsonProperty("source_uri") String sourceUri,
      @JsonProperty("started_at") @JsonFormat(shape = JsonFormat.Shape.STRING) Instant startedAt,
      @JsonProperty("finished_at") @JsonFormat(shape = JsonFormat.Shape.STRING)
          Instant finishedAt) {
    public ReadinessOutcome {
      if (revision == null && acquisition == null)
        throw new IllegalArgumentException("readiness needs a source identity");
      if (!Set.of("ready", "failed", "blocked", "cancelled", "skipped", "pending", "unavailable")
              .contains(state)
          || attempt != null && attempt < 0
          || generation != null && generation < 1)
        throw new IllegalArgumentException("invalid readiness outcome");
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Readiness(
      int expected,
      int settled,
      int ready,
      int pending,
      boolean complete,
      List<ReadinessOutcome> outcomes,
      Boolean interrupted) {
    public Readiness {
      outcomes = List.copyOf(outcomes);
      if (expected < 1
          || expected > 100
          || settled < 0
          || ready < 0
          || pending < 0
          || ready > settled
          || expected != outcomes.size()
          || expected != settled + pending
          || complete != (pending == 0))
        throw new IllegalArgumentException("invalid readiness counters");
    }

    public Readiness interruptedObserver() {
      return new Readiness(expected, settled, ready, pending, complete, outcomes, true);
    }
  }

  private static String text(String value, int max) {
    if (value == null || value.isBlank() || value.length() > max || value.indexOf('\0') >= 0)
      throw new IllegalArgumentException("invalid bounded information text");
    return value;
  }

  private static List<String> strings(List<String> values, int count, int length) {
    if (values.size() > count) throw new IllegalArgumentException("too many information values");
    return values.stream().map(v -> text(v, length)).distinct().toList();
  }
}
