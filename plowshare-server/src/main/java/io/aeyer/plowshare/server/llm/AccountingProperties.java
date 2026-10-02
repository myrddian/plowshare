package io.aeyer.plowshare.server.llm;

import java.time.Duration;
import org.springframework.util.unit.DataSize;

/** Durable capture is opt-in; enabling it requires a real server data directory and PostgreSQL projection. */
public class AccountingProperties {
    private boolean enabled;
    private DataSize maxJournalBytes = DataSize.ofMegabytes(256);
    private DataSize segmentBytes = DataSize.ofMegabytes(4);
    private Duration projectionInterval = Duration.ofSeconds(1);
    private Duration ioTimeout = Duration.ofSeconds(2);
    private int retryBufferEvents = 128;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public DataSize getMaxJournalBytes() { return maxJournalBytes; }
    public void setMaxJournalBytes(DataSize value) { maxJournalBytes = value; }
    public DataSize getSegmentBytes() { return segmentBytes; }
    public void setSegmentBytes(DataSize value) { segmentBytes = value; }
    public Duration getProjectionInterval() { return projectionInterval; }
    public void setProjectionInterval(Duration value) { projectionInterval = value; }
    public Duration getIoTimeout() { return ioTimeout; }
    public void setIoTimeout(Duration value) { ioTimeout = value; }
    public int getRetryBufferEvents() { return retryBufferEvents; }
    public void setRetryBufferEvents(int value) { retryBufferEvents = value; }

    public void validate() {
        if (maxJournalBytes == null || segmentBytes == null || segmentBytes.toBytes() < 1024
                || segmentBytes.toBytes() > maxJournalBytes.toBytes()) {
            throw new IllegalStateException("plowshare.llm.accounting needs segment-bytes >= 1KiB and <= max-journal-bytes");
        }
        if (projectionInterval == null || projectionInterval.toMillis() < 1
                || ioTimeout == null || ioTimeout.toMillis() < 1
                || retryBufferEvents < 1 || retryBufferEvents > 10_000) {
            throw new IllegalStateException("plowshare.llm.accounting needs intervals/timeouts >= 1ms and 1..10000 retry-buffer-events");
        }
    }
}
