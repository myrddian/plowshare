package io.aeyer.plowshare.server.applications;

import io.aeyer.plowshare.protocol.ApplicationIdentity.*;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Specialist repository. Mutations join the enclosing reservation, activation or issuance
 * transaction.
 */
@Repository
public class JdbcApplicationIdentityRepository implements ApplicationIdentityRepository {
  private final JdbcTemplate jdbc;

  public JdbcApplicationIdentityRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<Binding> find(String project, boolean lock) {
    return jdbc
        .query(
            "SELECT i.project_name,i.service_handle,i.role,i.token_id,i.state,t.principal_handle,t.credential_issued,t.expires_at,t.revoked_at,i.credential_request_id FROM application_identities i JOIN service_tokens t ON t.id=i.token_id WHERE i.project_name=?"
                + (lock ? " FOR UPDATE OF i,t" : ""),
            (row, n) ->
                new Binding(
                    row.getString(1),
                    new Declaration(row.getString(2), row.getString(3)),
                    row.getObject(4, UUID.class),
                    row.getString(6),
                    State.valueOf(row.getString(5)),
                    row.getBoolean(7),
                    row.getObject(8, OffsetDateTime.class),
                    row.getObject(9, OffsetDateTime.class),
                    row.getObject(10, UUID.class)),
            project)
        .stream()
        .findFirst();
  }

  public void reserve(String project, Declaration service, UUID tokenId, String actor) {
    jdbc.update(
        "INSERT INTO application_identities(project_name,service_handle,role,token_id,reserved_by) VALUES (?,?,?,?,?)",
        project,
        service.handle(),
        service.role(),
        tokenId,
        actor);
    if (jdbc.update("UPDATE service_tokens SET credential_issued=FALSE WHERE id=?", tokenId) != 1)
      throw new CallerFault("Reserved Application token is unavailable");
  }

  public void activate(Binding identity) {
    var ids =
        jdbc.queryForList(
            "SELECT id FROM projects WHERE name=? AND personal_owner IS NULL",
            Long.class,
            identity.project());
    if (ids.size() != 1) throw new CallerFault("Application project is unavailable");
    long projectId = ids.getFirst();
    if (jdbc.update(
            "UPDATE application_identities SET project_id=?,state='ACTIVE' WHERE project_name=? AND service_handle=? AND role=? AND token_id=? AND (state='RESERVED' OR project_id=?)",
            projectId,
            identity.project(),
            identity.service().handle(),
            identity.service().role(),
            identity.tokenId(),
            projectId)
        != 1) throw new CallerFault("Application project no longer matches its retained identity");
    if (identity.state() == State.RESERVED)
      jdbc.update(
          "INSERT INTO service_token_scopes(token_id,project_id,role) VALUES (?,?,?)",
          identity.tokenId(),
          projectId,
          identity.service().role());
  }

  public Optional<IssueRecord> receipt(UUID requestId) {
    return jdbc
        .query(
            "SELECT request_id,actor,project_name,token_id,expires_in_days,rotate,expires_at FROM application_token_receipts WHERE request_id=?",
            (row, n) ->
                new IssueRecord(
                    row.getString(2),
                    new Issue(
                        row.getString(3),
                        row.getObject(1, UUID.class),
                        row.getInt(5),
                        row.getBoolean(6)),
                    new Receipt(
                        row.getObject(1, UUID.class),
                        row.getString(3),
                        row.getObject(4, UUID.class),
                        "@service/" + row.getObject(4, UUID.class),
                        row.getObject(7, OffsetDateTime.class))),
            requestId)
        .stream()
        .findFirst();
  }

  public void issued(String actor, Issue request, Receipt receipt, String digest) {
    if (!request.requestId().equals(receipt.requestId())
        || !request.project().equals(receipt.project())
        || !digest.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("Invalid Application credential receipt");
    if (jdbc.update(
            "UPDATE service_tokens t SET digest=?,expires_at=?,revoked_at=NULL,credential_issued=TRUE FROM application_identities i WHERE i.token_id=t.id AND i.project_name=? AND i.state='ACTIVE' AND t.id=? AND t.credential_issued=?",
            digest,
            receipt.expiresAt(),
            request.project(),
            receipt.tokenId(),
            request.rotate())
        != 1)
      throw new CallerFault("Application credential state changed; inspect identity and receipts");
    if (jdbc.update(
            "UPDATE application_identities SET credential_request_id=? WHERE project_name=? AND token_id=?",
            request.requestId(),
            request.project(),
            receipt.tokenId())
        != 1) throw new CallerFault("Application credential identity changed");
    jdbc.update(
        "INSERT INTO application_token_receipts(request_id,actor,project_name,token_id,expires_in_days,rotate,expires_at) VALUES (?,?,?,?,?,?,?)",
        request.requestId(),
        actor,
        request.project(),
        receipt.tokenId(),
        request.expiresInDays(),
        request.rotate(),
        receipt.expiresAt());
  }
}
