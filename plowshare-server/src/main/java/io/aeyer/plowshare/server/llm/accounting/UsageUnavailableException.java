package io.aeyer.plowshare.server.llm.accounting;

import io.aeyer.plowshare.server.archive.ArchiveUnavailableException;

/** Database outage without driver text or a misleading successful empty report. */
public final class UsageUnavailableException extends ArchiveUnavailableException {
    public UsageUnavailableException(Throwable cause) {
        super("usage accounting could not be read", cause);
    }
}
