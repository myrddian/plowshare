package io.aeyer.plowshare.protocol;

/** Source metadata or one bounded byte range; never converted text. The server
 * verifies the complete content hash before conversion or cache insertion. */
public record FileSource(long size, String sha256, Integer offset, String data) {
    public static final int MAX_BYTES = 8 * 1024 * 1024;
    public static final int CHUNK_BYTES = 64 * 1024;

    public FileSource {
        if (size < 0 || size > MAX_BYTES) throw new IllegalArgumentException("source size outside bound");
        if (data == null) {
            if (offset != null || sha256 == null || !sha256.matches("[a-f0-9]{64}"))
                throw new IllegalArgumentException("unreadable source metadata");
        } else if (sha256 != null || offset == null || offset < 0 || offset > size
                || data.length() > ((CHUNK_BYTES + 2) / 3) * 4) {
            throw new IllegalArgumentException("unreadable source byte range");
        }
    }
}
