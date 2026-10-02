package io.aeyer.plowshare.server.llm.accounting;

/** Storage refusal with an allowlisted reason, never a provider response or SQL error message. */
public final class AccountingUnavailableException extends io.aeyer.plowshare.server.llm.dispatch.LlmException {
    private final AccountingJournal.Problem problem;

    public AccountingUnavailableException(AccountingJournal.Problem problem) {
        super("inference accounting unavailable: " + problem.name());
        this.problem = problem;
    }

    public AccountingJournal.Problem problem() { return problem; }
}
