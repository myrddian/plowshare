package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.archive.Archive;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@code memory.read} — one memory, in full, by id. The frame equivalent of {@code GET
 * /v1/memories/&#123;id&#125;}.
 *
 * <h2>{@code read} and not {@code get}, which is a decision and not a spelling</h2>
 *
 * <p>{@code Archive.get} touches no counters and exists for the archive's own internal lookups;
 * {@code Archive.read} counts the use. The endpoint calls {@code read} deliberately — a lookup an
 * agent makes by id is a use in the same sense a recall hit is, and a memory found only by id would
 * otherwise decay as if nothing had ever asked for it. A handler that reached for the quiet door
 * would answer with the identical memory and change what the archive forgets, which is a drift no
 * comparison of two answers can see.
 *
 * <p>An unknown id throws from inside the archive, before anything is saved, and {@code Faults}
 * turns that into a {@code NOT_FOUND} rather than an {@code OK} carrying a null — the same answer
 * the endpoint gives, in the archive's own words.
 *
 * <p><b>The id is a payload field named {@code memory}</b>, per {@link Payloads}' convention:
 * {@code id} at the envelope level already means the client's correlation, so a frame naming what
 * it acts on says the noun. A frame that names none is a request only this surface can receive — a
 * URL naming no memory is a different URL — so the refusal for it is this surface's own.
 */
public final class MemoryReadHandler implements FrameHandler {

  private final Archive archive;

  /**
   * @param archive the one archive both surfaces read a memory out of
   */
  public MemoryReadHandler(Archive archive) {
    this.archive = Objects.requireNonNull(archive, "archive");
  }

  @Override
  public Outcome handle(Map<String, Object> payload, Asking asking) {
    String memory =
        Payloads.required(
            payload,
            "memory",
            FrameTypes.MEMORY_READ,
            "the id memory.index answers with. Nothing was read.");
    return Outcome.ok(archive.read(List.of(memory)).get(0));
  }
}
