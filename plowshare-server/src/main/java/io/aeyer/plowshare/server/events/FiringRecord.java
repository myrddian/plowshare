package io.aeyer.plowshare.server.events;

import java.time.Instant;

public record FiringRecord(
        String id, String event, String data, String schedule, Instant fireAt, String trigger,
        String target, String status, String supersededBy, String reason, String jobId,
        Instant arrivedAt, Instant startedAt, Instant finishedAt, String topic) {}
