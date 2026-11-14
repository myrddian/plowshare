-- Statement statistics for deployment telemetry. The extension's views are created here;
-- collection additionally needs shared_preload_libraries=pg_stat_statements (see the compose
-- file). Idempotent because existing deployments may already have installed it by hand.
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
