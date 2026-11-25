ALTER TABLE personal_mailbox_subscriptions
    DROP CONSTRAINT personal_mailbox_subscriptions_source_type_check,
    ADD CONSTRAINT personal_subscription_source_type CHECK (
        source_type IN ('MAILBOX','RELAY','APPLICATION_INBOX','ATTENTION_COPY',
            'JOB','ORCHESTRATION','BOARD','INFORMATION'));
