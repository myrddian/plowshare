package io.aeyer.plowshare.server.orchestrations;

import io.aeyer.plowshare.protocol.Home;
import java.util.List;
import java.util.Objects;

/**
 * The acceptance checker (spec 2026-10-01, the acceptance checker §2–§3): an opt-in agent, named
 * by an orchestration definition's {@code checker:}, that the harness runs — never a conductor —
 * to hold the conductor to its acceptance. Hostile by instruction: the product does not work until
 * something outside the model shows it does.
 *
 * <p>It never writes acceptance, judges code quality or runs the product. What it answers is data
 * the harness validates and records; nothing it says is an instruction to anyone. An answer that
 * is not its JSON is {@link Unreadable}, which each caller turns into what the spec says: nothing
 * to say at plan time, and "cannot check — ask the person" at the end.
 */
public interface AcceptanceChecker {

    /** Has nothing to say, and can check nothing: every concern at the end is the person's. */
    AcceptanceChecker NONE = new AcceptanceChecker() {
        @Override
        public List<Raised> plan(Brief brief) {
            return List.of();
        }

        @Override
        public Judged judge(Brief brief, Concerns.Concern concern, String reason) {
            throw new Unreadable("no checker is wired on this server");
        }

        @Override
        public End end(Brief brief, List<Concerns.Concern> concerns) {
            throw new Unreadable("no checker is wired on this server");
        }
    };

    /**
     * What the checker is shown about a run, beside what it reads itself.
     *
     * @param run the run's id
     * @param checker the agent's name, from the run's pinned {@code checker:}
     * @param home the run's tier, which the checker's read-only file tools reach
     * @param session the session whose machine the project's files are on, or null
     * @param artifactsDir the run's artifacts directory, project-relative
     * @param spec spec.md's text, or a sentence saying why it could not be read
     * @param plan plan.md's text, or a sentence saying why it could not be read
     */
    record Brief(String run, String checker, Home home, String session, String artifactsDir,
            String spec, String plan) {
        public Brief {
            Objects.requireNonNull(run, "run");
            Objects.requireNonNull(checker, "checker");
            Objects.requireNonNull(home, "home");
        }
    }

    /**
     * A concern raised at plan time.
     *
     * @param about the requirement or plan item it is about
     * @param why why it is a concern
     * @param question the WHY to put to the conductor, or null to keep it for the end
     */
    record Raised(String about, String why, String question) {
    }

    /**
     * The checker's verdict on the conductor's answer to a WHY.
     *
     * @param resolved whether the reason holds
     * @param objection why it does not, or null when it does
     * @param question what it asks next, or null for nothing more
     */
    record Judged(boolean resolved, String objection, String question) {
    }

    /**
     * One concern as the end pass found it.
     *
     * @param concern the concern's id
     * @param verdict {@link Concerns#HOLDS}, {@link Concerns#DOES_NOT_HOLD} or {@link
     *     Concerns#CANNOT_CHECK}
     * @param finding what it found
     * @param personCheck for {@link Concerns#CANNOT_CHECK}, what the person should do and see
     */
    record Verdict(String concern, String verdict, String finding, String personCheck) {
    }

    /**
     * A concern the end pass raised itself, with its verdict.
     *
     * @param about the requirement or plan item it is about
     * @param why why it is a concern
     */
    record Found(String about, String why, String verdict, String finding, String personCheck) {
    }

    /**
     * What the end pass came to.
     *
     * @param verdicts one per concern it was shown that it answered for
     * @param found concerns it raised against the finished project
     */
    record End(List<Verdict> verdicts, List<Found> found) {
        public End {
            verdicts = List.copyOf(verdicts);
            found = List.copyOf(found);
        }
    }

    /** An answer that is not the checker's JSON, or no answer at all. */
    final class Unreadable extends RuntimeException {
        public Unreadable(String why) {
            super(why);
        }

        public Unreadable(String why, Throwable cause) {
            super(why, cause);
        }
    }

    /**
     * The plan pass: the goal, spec.md with its acceptance and plan.md, read hostile.
     *
     * @return the concerns it raises
     * @throws Unreadable when its answer is not its JSON
     */
    List<Raised> plan(Brief brief);

    /**
     * The conductor's answer to one of its WHY questions.
     *
     * @param reason the conductor's answer, as it gave it
     * @throws Unreadable when its answer is not its JSON
     */
    Judged judge(Brief brief, Concerns.Concern concern, String reason);

    /**
     * The end pass: every concern, against the finished project, read-only.
     *
     * @throws Unreadable when its answer is not its JSON
     */
    End end(Brief brief, List<Concerns.Concern> concerns);
}
