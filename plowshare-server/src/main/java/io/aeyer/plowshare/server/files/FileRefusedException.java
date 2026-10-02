package io.aeyer.plowshare.server.files;

import io.aeyer.plowshare.protocol.FileResult;
import java.util.Objects;

/**
 * A change to a file that was refused, with the facts the file side reported
 * and the sentence {@link FileWords} made of them.
 *
 * <p>A {@link WorkspaceRefusedException}, so every caller that already reads
 * one — the file tools, a mirror that rewrites its paths — reads this as the
 * correctable refusal it is and shows its message. The facts ride beside the
 * words for a caller that needs to act on which rule it was rather than on how
 * it was said.
 */
public final class FileRefusedException extends WorkspaceRefusedException {

    private final transient FileResult facts;

    /**
     * @param onClient whether the file is on a client's machine, which the words
     *     say; see {@link FileWords#refusal}
     */
    public FileRefusedException(FileResult facts, boolean onClient) {
        super(FileWords.refusal(Objects.requireNonNull(facts, "facts"), onClient));
        this.facts = facts;
    }

    public FileResult facts() {
        return facts;
    }
}
