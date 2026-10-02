package io.aeyer.plowshare.server.orchestrations;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/** {@link Concerns} in a list, for the gates' tests; {@link OrchestrationConcerns} is the SQL. */
final class InMemoryConcerns implements Concerns {

    final List<Concern> rows = new ArrayList<>();

    @Override
    public List<Concern> of(String run) {
        return rows.stream().filter(each -> each.orchestration().equals(run)).toList();
    }

    @Override
    public Concern raise(String run, String about, String why, String raised, String question) {
        Concern concern = new Concern(run, "c" + (of(run).size() + 1), about, why, raised,
                question == null ? OPEN : ASKED, question == null ? 0 : 1, question, null, null,
                null, null, null, null, Instant.EPOCH);
        rows.add(concern);
        return concern;
    }

    private void change(String run, String id, UnaryOperator<Concern> how) {
        for (int i = 0; i < rows.size(); i++) {
            Concern each = rows.get(i);
            if (each.orchestration().equals(run) && each.id().equals(id)) {
                rows.set(i, how.apply(each));
            }
        }
    }

    private static Concern with(Concern c, String state, int rounds, String question,
            String reason, String objection, String verdict, String finding, String personCheck,
            String personAnswer) {
        return new Concern(c.orchestration(), c.id(), c.about(), c.why(), c.raised(), state,
                rounds, question, reason, objection, verdict, finding, personCheck, personAnswer,
                Instant.EPOCH);
    }

    @Override
    public void answered(String run, String id, String reason) {
        change(run, id, c -> with(c, ANSWERED, c.rounds(), c.question(), reason, c.objection(),
                c.verdict(), c.finding(), c.personCheck(), c.personAnswer()));
    }

    @Override
    public void resolved(String run, String id) {
        change(run, id, c -> with(c, RESOLVED, c.rounds(), c.question(), c.reason(),
                c.objection(), c.verdict(), c.finding(), c.personCheck(), c.personAnswer()));
    }

    @Override
    public void askedAgain(String run, String id, String objection, String question) {
        change(run, id, c -> with(c, ASKED, Math.min(MOST_ROUNDS, c.rounds() + 1), question,
                c.reason(), objection, c.verdict(), c.finding(), c.personCheck(),
                c.personAnswer()));
    }

    @Override
    public void forThePerson(String run, String id, String objection) {
        change(run, id, c -> with(c, FOR_THE_PERSON, c.rounds(), c.question(), c.reason(),
                objection, null, c.finding(), c.personCheck(), c.personAnswer()));
    }

    @Override
    public void checked(String run, String id, String verdict, String finding,
            String personCheck) {
        change(run, id, c -> with(c, CANNOT_CHECK.equals(verdict) ? FOR_THE_PERSON : CHECKED,
                c.rounds(), c.question(), c.reason(), c.objection(), verdict, finding,
                personCheck, c.personAnswer()));
    }

    @Override
    public void personAnswered(String run, String id, String answer, boolean accepted) {
        change(run, id, c -> with(c, accepted ? RESOLVED : OPEN, c.rounds(), c.question(),
                c.reason(), c.objection(), c.verdict(), c.finding(), c.personCheck(), answer));
    }
}
