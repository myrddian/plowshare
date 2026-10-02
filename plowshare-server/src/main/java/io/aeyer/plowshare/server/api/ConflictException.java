package io.aeyer.plowshare.server.api;

/**
 * The caller asked this surface to do something it will not do to a row or a
 * file that is already there, and could correct it by asking differently.
 *
 * <p>The first and so far the only site is {@code POST /v1/agents}: a
 * definition already sits at the name a write named, and the request did not
 * carry {@code overwrite}. That is not {@link BadRequestException} — the body
 * was well formed and every field in it was valid — and it is not {@code
 * ArchiveRefusedException} either, on that type's own accounting: it carries a
 * closed list of every site classified as an archive refusal, and this one is
 * not an operation on an archive row at all, so widening it to cover a
 * filesystem write would make that list describe two layers and be checkable
 * at neither.
 *
 * <p>409 and not 400, because the caller can act on the distinction: a body
 * that will never load is wrong on its own terms and resending it unchanged
 * never succeeds, where a body that collided with an existing name succeeds
 * the moment the same request is resent with {@code overwrite} set. Folding
 * the two into one status would erase exactly the bit a retrying caller needs.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }

    public ConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
