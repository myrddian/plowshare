package io.aeyer.plowshare.server.acting;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.ProjectRole;
import io.aeyer.plowshare.server.personal.PersonalSpaces;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Specialist persistence for immutable bindings and monotonic, non-replaying receipts. */
public final class JdbcActingWorkRepository implements ActingWorkRepository {
  private final JdbcTemplate jdbc;
  private final ConversationStore conversations;
  private final ActingRepository consent;
  private final org.springframework.transaction.support.TransactionTemplate receiptWrites;

  public JdbcActingWorkRepository(
      JdbcTemplate jdbc,
      ConversationStore conversations,
      ActingRepository consent,
      org.springframework.transaction.PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    this.conversations = conversations;
    this.consent = consent;
    receiptWrites = new org.springframework.transaction.support.TransactionTemplate(manager);
    receiptWrites.setPropagationBehavior(
        org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  @Override
  public void lock() {
    transaction();
    consent.lock();
  }

  @Override
  public String open(String subject, Integer budget) {
    transaction();
    return conversations
        .open(
            Home.of(PersonalSpaces.name(subject)),
            io.aeyer.plowshare.server.embedding.SpendingPolicy.budget(budget),
            null,
            subject)
        .id();
  }

  @Override
  public Optional<Binding> binding(String conversation) {
    return jdbc
        .query(
            "SELECT * FROM acting_work_conversations WHERE conversation = ?",
            (rs, row) -> binding(rs),
            conversation)
        .stream()
        .findFirst();
  }

  @Override
  public boolean owns(Binding binding) {
    var current = binding(binding.conversation());
    if (current.isEmpty() || !current.get().equals(binding)) return false;
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            """
        SELECT EXISTS (
          SELECT 1 FROM acting_work_conversations b
          JOIN conversations c ON c.id=b.conversation AND c.project_id=b.personal_project_id
          JOIN projects p ON p.id=b.personal_project_id
          WHERE b.conversation=? AND c.owner_handle=? AND p.name=? AND c.origin='turn'
        )
        """,
            Boolean.class,
            binding.conversation(),
            binding.authority().subject(),
            PersonalSpaces.name(binding.authority().subject())));
  }

  @Override
  public boolean active(Binding binding) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM conversations WHERE id=? AND lifecycle='active')",
            Boolean.class,
            binding.conversation()));
  }

  @Override
  public void bind(Binding binding) {
    transaction();
    var authority = binding.authority();
    var delegation = authority.delegation();
    if (!delegation.scope().personal() || !delegation.scope().projects().isEmpty())
      throw new IllegalArgumentException("Personal-only binding required");
    var proof =
        consent
            .token(delegation.proof())
            .orElseThrow(() -> new IllegalArgumentException("unknown acting proof"));
    if (!proof.actor().equals(authority.actor())
        || !proof.subject().equals(authority.subject())
        || !proof.application().equals(delegation.application())
        || !proof.grant().equals(delegation.grant())
        || !proof.scope().personal()
        || !proof.scope().ceiling().allows(delegation.scope().ceiling()))
      throw new IllegalArgumentException("acting proof does not match binding");
    int changed =
        jdbc.update(
            """
        INSERT INTO acting_work_conversations
          (conversation,personal_project_id,actor,subject,application,grant_name,proof,ceiling)
        SELECT c.id,c.project_id,?,?,?,?,?,? FROM conversations c JOIN projects p ON p.id=c.project_id
        WHERE c.id=? AND c.owner_handle=? AND p.name=? AND c.origin='turn'
        """,
            authority.actor(),
            authority.subject(),
            delegation.application(),
            delegation.grant(),
            delegation.proof(),
            delegation.scope().ceiling().name(),
            binding.conversation(),
            authority.subject(),
            PersonalSpaces.name(authority.subject()));
    if (changed != 1) throw new IllegalStateException("Personal conversation binding refused");
  }

  @Override
  public Optional<Admission> receipt(String actor, UUID requestId) {
    return jdbc
        .query(
            """
        SELECT b.*,r.request_id,r.fingerprint,r.agent,r.state,r.job
        FROM acting_work_receipts r JOIN acting_work_conversations b USING(conversation)
        WHERE r.actor=? AND r.request_id=?
        """,
            (rs, row) -> admission(rs),
            actor,
            requestId)
        .stream()
        .findFirst();
  }

  @Override
  public Optional<Admission> job(String job) {
    return jdbc
        .query(
            """
        SELECT b.*,r.request_id,r.fingerprint,r.agent,r.state,r.job
        FROM acting_work_receipts r JOIN acting_work_conversations b USING(conversation)
        WHERE r.job=?
        """,
            (rs, row) -> admission(rs),
            job)
        .stream()
        .findFirst();
  }

  @Override
  public void admit(Admission admission) {
    transaction();
    var receipt = admission.receipt();
    if (receipt.state() != ActingWork.State.ADMITTED
        || receipt.job() != null
        || !admission.binding().conversation().equals(receipt.conversation())
        || !owns(admission.binding())) throw new IllegalArgumentException("invalid admission");
    jdbc.update(
        """
        INSERT INTO acting_work_receipts(actor,request_id,fingerprint,conversation,agent,state)
        VALUES(?,?,?,?,?,'ADMITTED')
        """,
        admission.binding().authority().actor(),
        receipt.requestId(),
        admission.fingerprint(),
        receipt.conversation(),
        receipt.agent());
  }

  @Override
  public void started(String actor, UUID requestId, String job) {
    if (job == null || !job.matches("job_[A-Za-z0-9]+"))
      throw new IllegalArgumentException("invalid job");
    int changed =
        receiptWrites.execute(
            status ->
                jdbc.update(
                    """
        UPDATE acting_work_receipts SET state='STARTED',job=?
        WHERE actor=? AND request_id=? AND state='ADMITTED' AND job IS NULL
        """,
                    job,
                    actor,
                    requestId));
    if (changed != 1) throw new IllegalStateException("Acting turn was already submitted");
  }

  @Override
  public void finished(String actor, UUID requestId) {
    receiptWrites.executeWithoutResult(
        status ->
            jdbc.update(
                "UPDATE acting_work_receipts SET state='FINISHED' WHERE actor=? AND request_id=? AND state IN ('STARTED','UNCERTAIN') AND job IS NOT NULL",
                actor,
                requestId));
  }

  @Override
  public void uncertain(String actor, UUID requestId) {
    receiptWrites.executeWithoutResult(
        status ->
            jdbc.update(
                "UPDATE acting_work_receipts SET state='UNCERTAIN' WHERE actor=? AND request_id=? AND state IN ('ADMITTED','STARTED')",
                actor,
                requestId));
  }

  private static Binding binding(ResultSet row) throws SQLException {
    return new Binding(
        row.getString("conversation"),
        new RequestAuthority.Delegated(
            row.getString("actor"),
            row.getString("subject"),
            new RequestAuthority.Delegation(
                row.getString("application"),
                row.getString("grant_name"),
                row.getObject("proof", UUID.class),
                new ActingScope(ProjectRole.valueOf(row.getString("ceiling")), Set.of(), true))));
  }

  private static Admission admission(ResultSet row) throws SQLException {
    return new Admission(
        binding(row),
        row.getString("fingerprint"),
        new ActingWork.Receipt(
            row.getObject("request_id", UUID.class),
            row.getString("conversation"),
            row.getString("agent"),
            ActingWork.State.valueOf(row.getString("state")),
            row.getString("job")));
  }

  private static void transaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("Acting admission requires a transaction");
  }
}
