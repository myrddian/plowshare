package io.aeyer.plowshare.server.api;

import io.aeyer.plowshare.server.agents.digests.Digests;
import io.aeyer.plowshare.server.agents.digests.Navigator;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The two verbs the archive's digests are reached by: ask one a question, and
 * build them.
 *
 * <h2>Both live under {@code /v1/memories/} and neither belongs to {@code
 * MemoryController}</h2>
 *
 * <p>Worth saying because the paths invite the mistake, and the breadth plan
 * made it once: a path prefix is not a controller. {@code MemoryController}
 * answers six verbs about individual memories; these two are about the digests
 * folded <em>over</em> them, which are built by an agent and read by one.
 *
 * <h2>Only a door: every decision is {@link Digests}'</h2>
 *
 * <p>This class held them all until the breadth plan's Task 6 — two budgets
 * read off the operator's configuration, the classification of how a pass
 * ended, the ordering that makes a blank project a refusal rather than a job
 * that fails on a worker thread, and one blank-question throw. Only the last of
 * those refused anything, so a frame handler written by copying the checks out
 * of this file would have got the other three wrong in silence. They are {@link
 * Digests}' now, which is what lets {@code memory.navigate} and {@code
 * memory.digest} answer the same thing this does by calling the same method.
 */
@RestController
public class DigestController {

    private final Digests digests;

    public DigestController(Digests digests) {
        this.digests = digests;
    }

    /** What both verbs take. {@code question} is read by {@link #navigate}
     *  alone — a pass folds a whole tier rather than answering anything, and
     *  {@code digest} has never looked at it. */
    public record Request(String project, String question) {}

    /**
     * {@code POST /v1/memories/navigate} — answer one question out of one
     * tier's digests, now.
     *
     * <p>200 and the result, including when the result is a refusal: {@link
     * Navigator.Result} carries {@code complete} and the text it got as far as,
     * and a navigation that ran out of allowance or found nothing is a finding
     * a caller reads rather than a request this route refuses. A malformed
     * request — no question, a blank project — is a caller mistake and is a 400
     * from {@link Digests}, through {@code ApiExceptionHandler}'s ordinary
     * mapping.
     */
    @PostMapping("/v1/memories/navigate")
    public Navigator.Result navigate(@RequestBody Request request) {
        return digests.navigate(request.project(), request.question());
    }

    /**
     * {@code POST /v1/memories/digest} — start a pass that folds one tier's
     * memories into digests, and answer with the handle at once.
     *
     * <p>202 and a job, because a pass is many model calls in series. It is
     * polled and cancelled through the same job verbs as any other run.
     */
    @PostMapping("/v1/memories/digest")
    public ResponseEntity<StartedJob> digest(@RequestBody Request request) {
        Digests.Started started = digests.start(request.project());
        return ResponseEntity.accepted().body(new StartedJob(started.id(), started.agent()));
    }
}
