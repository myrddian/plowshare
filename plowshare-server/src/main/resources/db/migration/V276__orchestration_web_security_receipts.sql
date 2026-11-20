ALTER TABLE orchestrations ADD COLUMN web_task text;
ALTER TABLE orchestration_script_steps ADD COLUMN web_review jsonb;
