package io.aeyer.plowshare.server.orchestrations;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The facts footer (spec 2026-09-29 §1a): what a delegate edited, ran and read, from its tool
 * lines in the record between its {@code delegated} and now. Nothing the model said is parsed;
 * nothing depends on its wording. Pure: {@link RecordKeeper} reads the rows.
 *
 * <h2>How a command that failed ended, and not only that it did</h2>
 *
 * <p>Measured 2026-09-30: a footer said {@code ran python -m pytest -q → exit 2} and nothing of
 * the output, and a phase conductor, then the root, then the caller were each asked for the
 * pytest output nobody had. So each command whose <em>latest</em> run did not end ok is followed
 * by the end of what it answered — {@code ToolLines.tail}, which the record keeps in the tool
 * line's body when the call returns — from the harness's own record of the run, never from what
 * the delegate said about it. A command that failed and then passed is not repeated: the footer
 * says the state the delegate left, and that one's latest word is ok.
 */
public final class DelegationFacts {

    private static final Set<String> EDITS = Set.of("file_edit", "file_delete", "file_move");

    /** How many edited paths, and how many commands, the footer names before it says "and N
     *  more" instead of naming a twentieth one. A read is a count already, so it needs none. */
    private static final int MOST_LISTED = 8;

    /** How many failed commands' output the footer shows, the latest first to go: each is up to
     *  {@code ToolLines.TAIL_CHARS}, and a caller reads this beside the delegate's own answer. */
    static final int MOST_TAILS = 3;

    /** A command's latest run: what it came to, when, and the end of its output. */
    private record Latest(String outcome, String at, String tail) {}

    /** One tool line, read back: the tool and its salient argument. */
    public record ToolLine(String tool, String said) {}

    private DelegationFacts() {
    }

    /**
     * @param callee the delegate, as the rows name it in {@code actor}
     * @param toolLines its tool lines, oldest first
     * @param zone the zone a command's time is shown in
     * @return {@code [harness] <callee> edited …; ran …[; read N files]} — each of the first two
     *     lists is cut at {@link #MOST_LISTED}, past which it ends {@code and N more}
     */
    public static String footer(String callee, List<RecordRow> toolLines, ZoneId zone) {
        return footer(callee, toolLines, zone, null);
    }

    /**
     * {@link #footer(String, List, ZoneId)} for a delegation inside a phase run, whose lines
     * {@link RecordKeeper} labels with the phase.
     *
     * @param callee the delegate, as the rows name it in {@code actor}
     * @param toolLines its tool lines, oldest first
     * @param zone the zone a command's time is shown in
     * @param phase the phase label its lines carry, or null for a run with none
     * @return as {@link #footer(String, List, ZoneId)}
     */
    public static String footer(String callee, List<RecordRow> toolLines, ZoneId zone,
            String phase) {
        DateTimeFormatter clock = DateTimeFormatter.ofPattern("HH:mm").withZone(zone);
        Set<String> edited = new LinkedHashSet<>();
        Set<String> read = new LinkedHashSet<>();
        List<String> ran = new ArrayList<>();
        Map<String, Latest> latest = new LinkedHashMap<>();
        for (RecordRow row : toolLines) {
            ToolLine line = parse(row, phase);
            // "…" for a null detail is on purpose, not a placeholder for a bug: a command still
            // running when the footer is built (RecordStore.settle races the footer's own read,
            // or the delegate is reported on mid-command) has genuinely not settled yet, and the
            // record has nothing else to say about it. It reads "ran <cmd> → … (<time>)" rather
            // than being silently dropped, so a person sees that it was started and is still
            // open — never "ok", which would claim a result the record does not have.
            String outcome = row.detail() == null ? "…" : row.detail();
            if (EDITS.contains(line.tool()) && "ok".equals(outcome)) {
                edited.add(line.said());
            } else if ("run".equals(line.tool())) {
                ran.add(line.said() + " → " + outcome + " (" + clock.format(row.at()) + ")");
                // Removed and put back, so the map's order is each command's latest run.
                latest.remove(line.said());
                latest.put(line.said(), new Latest(outcome, clock.format(row.at()), row.body()));
            } else if ("file_read".equals(line.tool()) && "ok".equals(outcome)) {
                read.add(line.said());
            }
        }
        List<String> editedList = List.copyOf(edited);
        String head = "[harness] " + callee + " ";
        String body = editedList.isEmpty() && ran.isEmpty() ? "edited nothing and ran nothing"
                : (editedList.isEmpty() ? "edited nothing"
                        : "edited " + bounded(editedList, false))
                        + "; " + (ran.isEmpty() ? "ran nothing" : "ran " + bounded(ran, true));
        String reads = read.isEmpty() ? ""
                : "; read " + read.size() + (read.size() == 1 ? " file" : " files");
        return head + body + reads + failures(latest);
    }

    /**
     * The end of the output of each command whose latest run did not end ok — ok and a command
     * still running have nothing to add — the {@link #MOST_TAILS} latest, from the record.
     */
    private static String failures(Map<String, Latest> latest) {
        List<String> shown = new ArrayList<>();
        int failed = 0;
        List<Map.Entry<String, Latest>> newestFirst = new ArrayList<>(latest.entrySet());
        Collections.reverse(newestFirst);
        for (Map.Entry<String, Latest> command : newestFirst) {
            Latest run = command.getValue();
            // "exit 0" is ok as ToolLines reads it; a record written by hand may spell it out.
            if ("ok".equals(run.outcome()) || "exit 0".equals(run.outcome())
                    || "…".equals(run.outcome())) {
                continue;
            }
            failed++;
            if (shown.size() < MOST_TAILS) {
                shown.add(0, "\n[harness] " + command.getKey() + " → " + run.outcome() + " ("
                        + run.at() + ")" + (run.tail() == null
                        ? "; the record holds none of its output"
                        : ": its output ended:\n" + run.tail()));
            }
        }
        String more = failed > shown.size() ? "\n[harness] and " + (failed - shown.size())
                + " more " + (failed - shown.size() == 1 ? "command" : "commands")
                + " did not end ok; the record has their output" : "";
        return String.join("", shown) + more;
    }

    /**
     * {@code items} joined by {@code ", "}, cut at {@link #MOST_LISTED} with {@code and N more}
     * past that — the first {@link #MOST_LISTED} for edited paths, the latest {@link
     * #MOST_LISTED} (a command list is already oldest first) for commands run, so a run's most
     * recent command is always the last one shown.
     *
     * @param items the list to render, already in the order it should read
     * @param keepLatest true to keep the list's tail instead of its head once it overflows
     * @return the rendered clause, with no leading or trailing word
     */
    private static String bounded(List<String> items, boolean keepLatest) {
        if (items.size() <= MOST_LISTED) {
            return String.join(", ", items);
        }
        List<String> shown = keepLatest ? items.subList(items.size() - MOST_LISTED, items.size())
                : items.subList(0, MOST_LISTED);
        return String.join(", ", shown) + " and " + (items.size() - MOST_LISTED) + " more";
    }

    /**
     * A tool line's tool and argument, from its text {@code [<phase> · ]<actor> · <tool>[ <said>]}.
     *
     * @param row a {@code tool_call} row
     * @return what it called, and with what
     */
    public static ToolLine parse(RecordRow row) {
        return parse(row, null);
    }

    /**
     * A tool line's tool and argument, its known phase label taken off first (final review):
     * the actor's marker is then matched where it must be, at the start, and not wherever it
     * first appears — a phase named {@code 03-coder} put {@code coder · } inside the label, and
     * the footer read the actor's own name as the tool.
     *
     * @param row a {@code tool_call} row
     * @param phase the phase label the row's text starts with, or null for none
     * @return what it called, and with what
     */
    public static ToolLine parse(RecordRow row, String phase) {
        String text = row.text();
        String label = phase == null ? null : phase + " · ";
        if (label != null && text.startsWith(label)) {
            text = text.substring(label.length());
        }
        String marker = row.actor() + " · ";
        int at = text.startsWith(marker) ? 0 : text.indexOf(marker);
        String rest = at < 0 ? text : text.substring(at + marker.length());
        int space = rest.indexOf(' ');
        return space < 0 ? new ToolLine(rest, "")
                : new ToolLine(rest.substring(0, space), rest.substring(space + 1));
    }
}
