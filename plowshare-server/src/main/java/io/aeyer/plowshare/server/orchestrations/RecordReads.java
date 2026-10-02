package io.aeyer.plowshare.server.orchestrations;

import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.requests.RequestedAfter;
import io.aeyer.plowshare.server.requests.RequestedBefore;
import io.aeyer.plowshare.server.requests.RequestedWindow;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * One reading of a tree's record, as {@code orchestration.record} and {@code GET
 * /v1/orchestrations/{id}/record} both take it — spec 2026-09-28 §3. The window and the two
 * directions are the log reads' own ({@link RequestedWindow}, {@link RequestedAfter}, {@link
 * RequestedBefore}); {@code id} may name any run in the tree and resolves to the root; a run that
 * is not the asking account's reads as one that does not exist, {@code OrchestrationFrames}'
 * own rule.
 */
public class RecordReads {

    /** A page, the root it was read from, and the limit used. */
    public record Read(String root, RecordPage page, int limit) {}

    private final RecordStore records;
    private io.aeyer.plowshare.server.information.InformationJobs inputs;
    public void useInformationInputs(io.aeyer.plowshare.server.information.InformationJobs inputs) { this.inputs=inputs; }

    public RecordReads(RecordStore records) {
        this.records = Objects.requireNonNull(records, "records");
    }

    /**
     * @throws CallerFault for no account, no id, a run this account does not own, a kind nobody
     *     spells, or a window that names two directions or none that exists
     */
    public Read read(String handle, String id, Integer after, Integer before, Boolean tail,
            Integer limit, List<String> kinds) {
        if (handle == null || handle.isBlank()) {
            throw new CallerFault("the record is read as an account, and this request is not"
                    + " signed in as one; nothing was read.");
        }
        if (id == null || id.isBlank()) {
            throw new CallerFault("the record needs the id of a run in its tree; nothing was"
                    + " read.");
        }
        RequestedWindow window = RequestedWindow.in(null, limit);
        int from = RequestedAfter.in(after);
        int below = RequestedBefore.in(before, tail, after);
        Set<RecordKind> narrowed = kindsIn(kinds);
        RecordStore.Tree tree = records.treeOfRun(id)
                .filter(found -> handle.equals(found.handle()))
                .orElseThrow(() -> new CallerFault(
                        "No orchestration with that id is owned by this account."));
        if(inputs!=null) inputs.requireLog(tree.root(),handle);
        RecordPage page = below != RequestedBefore.FORWARD
                ? records.pageBefore(tree.root(), below, narrowed, window.most())
                : records.pageAfter(tree.root(), from, narrowed, window.most());
        return new Read(tree.root(), page, window.most());
    }

    /** The kinds a request named, or every kind for none — {@code RequestedKinds}' rule. */
    private static Set<RecordKind> kindsIn(List<String> kinds) {
        if (kinds == null) {
            return RecordKind.EVERY;
        }
        if (kinds.isEmpty()) {
            throw new CallerFault("'kinds' names the record kinds to read, and an empty list"
                    + " reads nothing. Leave it out to read every kind.");
        }
        Set<RecordKind> named = EnumSet.noneOf(RecordKind.class);
        for (String kind : kinds) {
            named.add(RecordKind.of(kind).orElseThrow(() -> new CallerFault(
                    "no record kind is spelled '" + kind + "'; 'kinds' takes "
                            + Arrays.stream(RecordKind.values()).map(RecordKind::wire)
                                    .collect(Collectors.joining(", ")) + ".")));
        }
        return named;
    }
}
