-- Before Personal run selection was corrected, account-knowledge reads could be retained under
-- the owner's Personal runtime project. Retiring LEGACY_SHARED then stranded those histories:
-- the owner still has current account READ, but the recorded project selector no longer finds it.
-- Normalize only that exact owner/home alias, including copies inherited by parent logs. Retain
-- every input revision, owner and include-Global ceiling; do not publish resources, add links or
-- touch ordinary/foreign project selections. Current lifecycle, dependency, privacy and quarantine
-- checks still run through information_log_readable after this transactional Flyway upgrade.
UPDATE information_job_inputs AS input
SET scope='personal', project_name=NULL
FROM projects personal, information_revisions revision
JOIN information_resources resource ON resource.id=revision.resource_id
JOIN authority_scopes home ON home.id=resource.home_scope_id
JOIN authority_accounts owner ON owner.id=home.account_id
WHERE input.revision_id=revision.id AND input.scope='project'
  AND personal.name=input.project_name AND personal.personal_owner=input.owner_handle
  AND home.kind='ACCOUNT_KNOWLEDGE' AND home.builtin IS NULL
  AND owner.legacy_handle=input.owner_handle AND resource.owner_handle=input.owner_handle;
