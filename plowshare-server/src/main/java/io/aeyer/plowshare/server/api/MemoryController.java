package io.aeyer.plowshare.server.api;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.Memory;
import io.aeyer.plowshare.protocol.MemoryProposal;
import io.aeyer.plowshare.protocol.WriteResult;
import io.aeyer.plowshare.server.agents.scribe.Scribe;
import io.aeyer.plowshare.server.archive.Archive;
import io.aeyer.plowshare.server.archive.TocEntry;
import io.aeyer.plowshare.server.archive.Validation;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.server.requests.RequestedHome;
import io.aeyer.plowshare.server.requests.RequestedInvalidation;
import io.aeyer.plowshare.server.requests.RequestedMemoryQuestion;
import io.aeyer.plowshare.server.requests.RequestedProposal;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The HTTP surface between the client and the archive.
 *
 * <p>Every method here is a thin translation from a request onto one {@link Archive} call — the
 * archive already does everything; this class decides only the wire shape.
 *
 * <h2>Where the transaction went</h2>
 *
 * <p>Every method here used to carry {@code @Transactional}, and none does now. The reasoning
 * behind them was sound — a supersession is two saves that must not half-apply, and a batch read is
 * n saves that must count all or none — but the boundary was in the wrong place. An annotation
 * wraps a whole method, and these methods reach {@code Archive} calls that talk to an embedding
 * model, so the model call was inside the write transaction. That lost writes outright: any
 * exception from the embedding client that was not an {@code EmbeddingException} escaped {@code
 * Archive.embed}'s deliberately narrow catch and rolled the committed row back. It also pinned a
 * pooled database connection for the length of every model stall — eighty seconds, worst case,
 * against a default pool of ten.
 *
 * <p>The boundary now lives inside {@link Archive}, drawn around the statements that belong
 * together and closed before the model is called, through {@link
 * io.aeyer.plowshare.server.archive.UnitOfWork} — which carries the full account, including why an
 * after-commit hook was not the answer either. <b>Do not put {@code @Transactional} back on these
 * methods.</b> It would put the model call back inside a transaction and re-open both bugs, and it
 * would nest a second transaction around the archive's own.
 *
 * <h2>What {@code Home} a request means</h2>
 *
 * <p>Every endpoint that names a tier does so with one nullable {@code project} field: {@code null}
 * — whether the key was omitted or sent explicitly, Jackson cannot tell the two apart and this
 * class does not try — means {@link Home#global()}. A non-null, blank string is refused with 400
 * rather than folded into global, matching {@code Home.of}'s own contract: an omitted field and a
 * present-but-empty one must not resolve to different tiers, or a client that stringifies a missing
 * value as {@code ""} would silently write into the tier every project reads.
 */
@RestController
public class MemoryController {

  /**
   * There is no Excalibur constant to port for this one — {@code recall} as a vector query has no
   * Python equivalent, per {@code Archive}'s own javadoc ("the librarian is not ported"). Ten
   * matches Anchor's own {@code RetrieveController} default for the same shape of call.
   */
  static final int DEFAULT_RECALL_LIMIT = 10;

  private final Archive archive;
  private final Scribe scribe;

  public MemoryController(Archive archive, Scribe scribe) {
    this.archive = archive;
    this.scribe = scribe;
  }

  /**
   * {@code POST /v1/memories} — judge a proposal's shape, then file it.
   *
   * <p>Always 200: {@code WriteResult.memoryId} is never null, per its own javadoc — nothing is
   * ever refused at this layer, only judged for shape.
   *
   * <p><b>The verdict is made here and no longer sent by the caller.</b> {@link Scribe#judge} never
   * throws and never names a target it was not shown, so the {@code ArchiveException} {@code
   * applyVerdict} raises for a bad target is now unreachable through this endpoint — {@link
   * ApiExceptionHandler} still maps it, because {@code applyVerdict} is a public method with other
   * callers and a 404 is the right answer if one of them ever arrives here.
   *
   * <p><b>Validated before it is judged</b>, which is why {@code Validation.check} is called here
   * and not left to {@code applyVerdict} alone. Judging first meant a malformed proposal — one of
   * five refusals, of which an oversized body is the most expensive prompt of the lot — cost an
   * embedding call and a model call before coming back 422. {@code Scribe.judge} keeps its own
   * blank-summary guard because it is public and must not spend a call for any caller; this is what
   * stops the other four costing one. The archive checks again, because {@code applyVerdict} is a
   * public method with other callers and its own contract to keep, and the check reads nothing and
   * allocates nothing.
   */
  @PostMapping("/v1/memories")
  public ResponseEntity<WriteResult> write(
      @RequestBody WriteMemoryRequest request,
      @org.springframework.web.bind.annotation.RequestAttribute(
              name = io.aeyer.plowshare.server.auth.AuthFilter.HANDLE_ATTRIBUTE,
              required = false)
          String handle) {
    // Refused, not ignored, and refused before anything is written. A stale
    // client sending this used to get a 200 and go away believing it had
    // retired a memory that is still active and still answering recalls;
    // the field was never honoured, so what failed was that nobody was told.
    // The refusal itself is RequestedProposal.toFile's, so that the frame
    // surface refuses the same body in the same words rather than in a
    // second copy of this paragraph. See WriteMemoryRequest.verdict, which
    // exists only to make that sentence reachable.
    MemoryProposal proposal = RequestedProposal.toFile(request.proposal(), request.verdict());
    Home home = RequestedHome.in(request.project());
    Validation.check(proposal, archive.maxBodyChars());
    // One embedding call, not two, and it used to be two. Scribe.judge
    // embeds its candidate question with the same text the memory will be
    // embedded for, so it hands the vector back and applyVerdict stores it
    // rather than asking the endpoint again. The second call was paid on
    // EVERY write past the three guards in Scribe.judged -- the candidate
    // query happens before the empty-candidate short circuit -- so this is
    // the common path and not an optimisation for the rare one. See
    // Archive.applyVerdict(…, Precomputed) for the guard that makes reusing
    // it safe, and Scribe.Judgement for when there is no vector to reuse.
    var owner =
        archive.accountingEnabled()
            ? archive.usage(home, handle, UsageAttribution.Operation.REVIEW)
            : UsageAttribution.LEGACY;
    Scribe.Judgement judged =
        archive.accountingEnabled()
            ? scribe.judge(proposal, home, owner)
            : scribe.judge(proposal, home);
    WriteResult result =
        archive.accountingEnabled()
            ? archive.applyVerdict(
                proposal, judged.verdict(), home, judged.embedding(), id -> {}, owner)
            : archive.applyVerdict(proposal, judged.verdict(), home, judged.embedding());
    return ResponseEntity.ok(result);
  }

  /**
   * {@code POST /v1/memories/recall} — the memories nearest this question.
   *
   * <p>The response carries {@code unsearchable} alongside the hits: how many live memories in the
   * searched tiers have no embedding and so could not be looked at. It is not decoration. Without
   * it an empty {@code memories} is the same wire shape whether the archive holds nothing or holds
   * the answer with no vector on it, and only one of those is worth rephrasing the question for.
   */
  @PostMapping("/v1/memories/recall")
  public ResponseEntity<RecallResponse> recall(
      @RequestBody RecallRequest request,
      @org.springframework.web.bind.annotation.RequestAttribute(
              name = io.aeyer.plowshare.server.auth.AuthFilter.HANDLE_ATTRIBUTE,
              required = false)
          String handle) {
    String question = RequestedMemoryQuestion.recalled(request.question());
    Home home = RequestedHome.in(request.project());
    int limit = request.limitOrDefault();

    Archive.Recall recalled =
        archive.accountingEnabled()
            ? archive.recall(
                question,
                home,
                limit,
                archive.usage(home, handle, UsageAttribution.Operation.EMBEDDING_QUERY))
            : archive.recall(question, home, limit);
    return ResponseEntity.ok(
        new RecallResponse(question, limit, recalled.memories(), recalled.unsearchable()));
  }

  /**
   * {@code POST /v1/memories/reembed?project=} — give a vector to every live memory in one tier
   * that has none.
   *
   * <p>The repair path for what {@code unsearchable} makes visible on the two reads above. A memory
   * written while the embedding endpoint was down is complete except for its vector; once the
   * endpoint is back, the vector is one call away, and nothing was asking for it.
   *
   * <p><b>Operator-facing, and not an MCP tool.</b> Re-embedding is maintenance somebody does after
   * fixing an endpoint, not a judgement an agent should make in the middle of its own work — and it
   * costs one model call per memory, which is not a bill a recall should be able to run up by
   * accident.
   */
  @PostMapping("/v1/memories/reembed")
  public ResponseEntity<Archive.Repair> reembed(
      @RequestParam(required = false) String project,
      @org.springframework.web.bind.annotation.RequestAttribute(
              name = io.aeyer.plowshare.server.auth.AuthFilter.HANDLE_ATTRIBUTE,
              required = false)
          String handle) {
    return ResponseEntity.ok(
        archive.accountingEnabled()
            ? archive.reembed(
                RequestedHome.in(project),
                archive.usage(
                    RequestedHome.in(project), handle, UsageAttribution.Operation.EMBEDDING_REPAIR))
            : archive.reembed(RequestedHome.in(project)));
  }

  /**
   * {@code GET /v1/memories/{id}} — one memory, in full, by id.
   *
   * <p>Calls {@code Archive.read}, not {@code Archive.get}: {@code get} is documented as touching
   * no counters, for {@code Archive}'s own internal use ({@code requireTarget}) — a lookup an agent
   * makes through this endpoint is a use in the same sense a recall hit is, and skipping the count
   * here would mean a memory found only by id, never by recall, decays as if nothing ever asked for
   * it. This makes the endpoint a plain HTTP {@code GET} with a side effect, which is a real
   * tension with REST convention; it is resolved in favour of matching Excalibur, where the
   * equivalent harness-facing operation ({@code MemoryService.read}, listed with {@code index} and
   * {@code invalidate} as one of the three "open stacks" operations that touch no model) counts the
   * same way.
   *
   * <p>An unknown id throws {@code ArchiveException} from inside {@code Archive.get}, before
   * anything is saved; {@link ApiExceptionHandler} turns that into 404 rather than a 200 with a
   * null body — the plan's own required test.
   */
  @GetMapping("/v1/memories/{id}")
  public ResponseEntity<Memory> get(@PathVariable String id) {
    Memory memory = archive.read(List.of(id)).get(0);
    return ResponseEntity.ok(memory);
  }

  /**
   * {@code GET /v1/memories/index?project=} — one tier's index: {@code active} memories only, as
   * summary lines with no body.
   */
  @GetMapping("/v1/memories/index")
  public ResponseEntity<List<TocEntry>> index(@RequestParam(required = false) String project) {
    Home home = RequestedHome.in(project);
    return ResponseEntity.ok(archive.index(home));
  }

  /**
   * {@code POST /v1/memories/{id}/invalidate} — record that a memory stopped being true. The memory
   * is kept; only its state changes.
   */
  @PostMapping("/v1/memories/{id}/invalidate")
  public ResponseEntity<Memory> invalidate(
      @PathVariable String id, @RequestBody InvalidateRequest request) {
    Memory dead =
        archive.invalidate(
            id,
            RequestedInvalidation.reason(request.reason()),
            RequestedInvalidation.by(request.by()));
    return ResponseEntity.ok(dead);
  }

  public ResponseEntity<WriteResult> write(WriteMemoryRequest request) {
    return write(request, null);
  }

  public ResponseEntity<RecallResponse> recall(RecallRequest request) {
    return recall(request, null);
  }

  public ResponseEntity<Archive.Repair> reembed(String project) {
    return reembed(project, null);
  }
}
