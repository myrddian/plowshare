package io.aeyer.plowshare.server.images;

import io.aeyer.plowshare.protocol.FileAccess;
import io.aeyer.plowshare.protocol.Home;
import java.nio.file.Path;

/**
 * Whether a tier may still read a file of its own — asked when a <b>workspace</b>
 * image is resolved, and asked about nothing else.
 *
 * <h2>Why an uploaded image needs no fence and a workspace one does</h2>
 *
 * <p>{@link ImageStore}'s own javadoc says the data directory is a mandatory
 * exclusion of every file scope, so an uploaded image has its own boundary —
 * {@code home} plus an unguessable id — and deliberately never mentions {@link
 * FileAccess}. That separation is right for bytes this server owns.
 *
 * <p><b>It would be wrong for bytes it does not.</b> A workspace image is named
 * out of a file {@code file_read} was allowed to open, and the id outlives the
 * read: without this, a path {@code FileAccess.permits} allowed once would stay
 * resolvable after the root was unlent, after an exclusion was added, after the
 * hidden-component rule would now refuse it. Content addressing sharpens it —
 * the same bytes anywhere are the same id, so an id is globally meaningful while
 * permission is per path.
 *
 * <p><b>And the better reason is not containment at all.</b> Enzo, 2026-09-08:
 * <i>"path check still happens because we don't know if it exists."</i> The
 * bytes are not in this server's keeping, so resolution has to touch the path to
 * know they are still there — and touching the path is where the fence already
 * is. The liveness check and the permission check are one act, which is why
 * there is one seam for them rather than two.
 *
 * <h2>The project's fence, not the reading agent's grant</h2>
 *
 * <p>What is asked here is the <em>project's</em> — its roots and its effective
 * exclusions, which is what an unlent root, a new exclusion and a hidden
 * component all are. An agent's own {@code scopes:} grant is not consulted and
 * must not be: the grant was spent at the {@code file_read} that named the id,
 * and a run resolving an id it was handed may be a different agent entirely.
 * The tier is the containment — {@code AgentRunTool} takes {@code home} as a
 * parameter of {@code run} and no model argument can carry a second one — and
 * this narrows within it rather than replacing it.
 *
 * <p>The production binding is {@code ImagesConfig}, over {@code
 * ProjectStore.fence}, which is the <b>same</b> expression {@code LocalProvider}
 * reads a file through. One expression and not two: the copy that drifts is the
 * one deciding what an agent may read.
 */
@FunctionalInterface
public interface ImageFence {

    /**
     * A fence that permits nothing, which is what a store with no fence gets.
     *
     * <p>Not a null at the call site, on {@link ImageStore#NONE}'s reasoning.
     * <b>And it is not merely a safe default: a store holding this one names no
     * workspace image at all</b> — {@link ImageStore#note} answers null under it
     * — so there is no way to write a record that could never afterwards be
     * resolved. The two halves are wired together or neither exists.
     */
    ImageFence NOTHING = (home, file) -> false;

    /**
     * Whether this tier may read this file <em>now</em>.
     *
     * @param home the tier the resolving run belongs to. Never a model's choice
     * @param file the canonical path recorded when the image was named. Already
     *     known to exist — {@link ImageStore} asks liveness first, so that a
     *     deleted file is called gone rather than refused
     */
    boolean permits(Home home, Path file);
}
