-- Statement statistics for deployment telemetry. pg_stat_statements is not a trusted extension, so
-- creating it needs a superuser (or equivalent) role and the module must be installed. The extension
-- is optional telemetry: when it is unavailable or this role may not create it, the migration
-- reports a notice and continues so the server still starts. Collection additionally needs
-- shared_preload_libraries=pg_stat_statements (see the compose file). Idempotent because existing
-- deployments may already have installed it by hand.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_stat_statements') THEN
    RETURN;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_available_extensions WHERE name = 'pg_stat_statements') THEN
    RAISE NOTICE 'pg_stat_statements is not available on this server; skipping';
  ELSIF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = current_user AND rolsuper) THEN
    RAISE NOTICE 'role % may not create pg_stat_statements (superuser required); skipping',
      current_user;
  ELSE
    CREATE EXTENSION pg_stat_statements;
  END IF;
END
$$;
