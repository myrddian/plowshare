package io.aeyer.plowshare.server.files;

import io.aeyer.plowshare.protocol.FileAccess;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One line of an agent's {@code scopes:} declaration: a place, and how much of
 * it.
 *
 * <p><b>A place and not a directory.</b> One {@code workspace} grant resolves to
 * every directory the job's project names — its workspace and whatever is lent
 * alongside it — because the place is the project, and how many directories the
 * project is at that place is the {@code projects} row's business and never the
 * definition's. That is why lending needed no new scope and no new mode.
 *
 * <p><b>A {@code Grant} never reaches a file tool.</b> It is resolved once, when
 * a job starts, into the concrete list of directories that job may touch and one
 * boolean for whether it may write — Excalibur's argument, ported: a check that
 * has to interpret policy at call time is a check that will eventually interpret
 * it differently in two places. {@link FileAccess} is that resolved form.
 *
 * @param scope the place
 * @param mode how much of it
 */
public record Grant(Scope scope, Mode mode) {

    /**
     * Does this grant cover what a caller wants to do?
     *
     * <p>Write implies read; read does not imply write. The asymmetry is the
     * whole of the distinction between the two modes, and it lives here alone —
     * a caller that compared modes itself would be the second copy of it, and
     * the pair that drifts is the pair deciding whether a file may be opened for
     * writing. {@code Mode}, {@code LocalProvider} and {@code AgentRegistry} all
     * point here rather than restating that; they used to restate it.
     */
    public boolean allows(Mode wanted) {
        return mode == Mode.WRITE || wanted == Mode.READ;
    }

    /**
     * What to tell a model whose agent declared no grant at all.
     *
     * <p><b>A fact about an empty {@code List<Grant>}, so it lives beside the
     * other one</b> — {@link #parseAll}'s duplicate rule, which is here on
     * exactly that argument. Two providers reach this state and neither is the
     * better owner: {@link LocalProvider} finds it before it has looked at a
     * tier, and {@link RemoteProvider} finds it before a byte goes on the wire.
     * A copy in each is a copy that drifts, and it would drift on the one
     * sentence that sends a reader to the agent's own file rather than to a
     * workspace, an operator or a tier.
     *
     * <p>It names {@code scopes:} rather than "permissions" or "access" because
     * that is the key an operator has to type. {@link #parse}'s catalogue is the
     * other half of the same correction: say what is wrong, and say what to
     * write instead.
     */
    public static String noneDeclared() {
        return "this agent was granted no workspace: its definition's 'scopes:' are the"
                + " thing to change";
    }

    /**
     * How this grant is written in a {@code scopes:} line: {@code
     * workspace:read}.
     *
     * <p>The inverse of {@link #parse}, and the reason both live here: the
     * loader refuses a bad declaration and the non-escalation check names a
     * grant an agent may not delegate, so the spelling is read in one place and
     * printed in two others. A message that composed it itself would be the copy
     * that drifts, and it would drift in the direction of telling an operator to
     * write something the parser rejects.
     */
    public String declaration() {
        return token(scope) + ":" + token(mode);
    }

    /**
     * Reads one entry of a {@code scopes:} list.
     *
     * <p><b>The spelling is the spec's: {@code scopes: [workspace:read]}</b>, a
     * list of {@code scope:mode} strings. Excalibur writes a bare name in a list
     * to mean read, and that is refused here: a mode nobody wrote is a right
     * nobody can be held to. Its other spelling, a mapping, never reaches this
     * method at all — {@code [workspace: read]} with one space is not a list of
     * strings to a YAML parser, so the loader refuses it before there is a
     * string to parse, and {@code AgentRegistry} owns what that operator is
     * told.
     *
     * <p>Case-sensitive, on {@code AgentRegistry.requirePositiveInt}'s rule for
     * budgets: it is the value they wrote or nothing. The alternative is a
     * lenient reading that accepts four spellings of one grant and leaves a
     * reader of the next definition unsure which are legal.
     *
     * @throws IllegalArgumentException naming which half is unreadable, and
     *     every declaration that would have been legal. The exception carries no
     *     file: this class does not know it came from one, and the loader is
     *     what wraps it in the sentence naming the file to open
     */
    public static Grant parse(String declaration) {
        int colon = declaration.indexOf(':');
        if (colon < 0) {
            throw new IllegalArgumentException("'" + declaration + "' is not a scope and a mode"
                    + " separated by a colon" + catalogue());
        }
        String scope = declaration.substring(0, colon);
        String mode = declaration.substring(colon + 1);
        // The first colon separates, so `workspace:read:write` is a bad mode
        // rather than a bad scope: the half a reader wrote deliberately is the
        // half that is believed.
        for (Scope candidate : Scope.values()) {
            if (token(candidate).equals(scope)) {
                return new Grant(candidate, mode(declaration, mode));
            }
        }
        throw new IllegalArgumentException("'" + declaration + "' names the scope '" + scope
                + "', which does not exist" + catalogue());
    }

    /**
     * Reads a whole {@code scopes:} list, and refuses one that names a scope
     * twice.
     *
     * <p><b>The duplicate rule lives here because it is a fact about a {@code
     * List<Grant>} and not about a file.</b> Its twin is {@link
     * #allows}: {@code LocalProvider} asks whether <em>any</em> grant in the
     * list allows a write, so two grants over one scope mean the wider of the
     * two and a list reading as mostly-read carries write. Excalibur refuses the
     * same shape for the same reason. In the loader it was a rule about
     * frontmatter that a caller assembling grants in Java never met; here, both
     * arrive at the same answer.
     *
     * @throws IllegalArgumentException naming the entry that could not be read,
     *     or the pair that collided. It carries no file, for the reason {@link
     *     #parse} gives
     */
    public static List<Grant> parseAll(List<String> declarations) {
        List<Grant> grants = new ArrayList<>();
        for (String declaration : declarations) {
            Grant grant = parse(declaration);
            for (Grant held : grants) {
                if (held.scope() == grant.scope()) {
                    throw new IllegalArgumentException("it declares two grants over one scope, '"
                            + held.declaration() + "' and '" + grant.declaration() + "'. Together"
                            + " they mean the wider of the two, so the list says less access than"
                            + " it gives: say it once, with the rights that were meant");
                }
            }
            grants.add(grant);
        }
        return List.copyOf(grants);
    }

    private static Mode mode(String declaration, String mode) {
        for (Mode candidate : Mode.values()) {
            if (token(candidate).equals(mode)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("'" + declaration + "' names the mode '" + mode
                + "', which does not exist" + catalogue());
    }

    /**
     * Every declaration this parser accepts, said the same way in all three
     * refusals.
     *
     * <p>Derived from the two enums rather than written out, so a scope added
     * later appears in all three without anybody remembering to add it. A
     * message listing the legal values is the correction rather than the
     * complaint, and a hand-written one is the copy that goes stale first.
     */
    private static String catalogue() {
        List<String> known = new ArrayList<>();
        for (Scope scope : Scope.values()) {
            for (Mode mode : Mode.values()) {
                known.add(new Grant(scope, mode).declaration());
            }
        }
        return "; the grants an agent may declare are " + known;
    }

    /**
     * The frontmatter token for one enum constant.
     *
     * <p>The constant's own name, lowercased — so the two vocabularies cannot
     * drift, and so there is nowhere to write a third spelling. {@code
     * Locale.ROOT} because a Turkish default locale lowercases {@code I} to a
     * dotless {@code ı} and the frontmatter language is not the operator's.
     * {@code a_grant_is_written_scope_colon_mode} is what holds the tokens
     * themselves, since renaming a constant would otherwise change the language
     * every deployed agent file is written in with nothing to say so.
     */
    private static String token(Enum<?> constant) {
        return constant.name().toLowerCase(Locale.ROOT);
    }
}
