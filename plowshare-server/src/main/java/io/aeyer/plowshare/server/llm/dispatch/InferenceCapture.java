package io.aeyer.plowshare.server.llm.dispatch;

import java.util.UUID;

/**
 * Correlates a returned result with its logical call; false means terminal metadata is incomplete
 * or buffered.
 */
public record InferenceCapture(UUID callId, boolean durable) {}
