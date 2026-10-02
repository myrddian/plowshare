package io.aeyer.plowshare.server.archive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.server.hooks.HookFile;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Local hook sets, stored by the hash of what they hold (spec 2026-09-30-local-hooks-are-served
 * §3), as {@code system_blocks} stores opening blocks: a person opening fifty conversations with
 * the same hooks stores one set. Nothing here deletes one; V73's foreign key keeps every set a
 * log names.
 */
public final class LocalHookSetStore {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final Supplier<Instant> now;

    public LocalHookSetStore(JdbcTemplate jdbc) {
        this(jdbc, Instant::now);
    }

    public LocalHookSetStore(JdbcTemplate jdbc, Supplier<Instant> now) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.now = Objects.requireNonNull(now, "now");
    }

    /**
     * Store a set, once, and name it.
     *
     * <p>CAST(? AS JSONB) for {@code EntryStore}'s reason: the driver binds a String as varchar.
     *
     * @return {@link HookFile#hashOf}, whether this call wrote the row or it was already there
     */
    public String remember(List<HookFile> files) {
        Objects.requireNonNull(files, "files");
        String hash = HookFile.hashOf(files);
        String canonical = HookFile.canonical(files);
        ArchiveUnavailableException.translating("store a local hook set",
                () -> jdbc.update("INSERT INTO local_hook_sets (hash, files, created_at)"
                        + " VALUES (?, CAST(? AS JSONB), ?) ON CONFLICT (hash) DO NOTHING",
                        hash, canonical, now.get().atOffset(ZoneOffset.UTC)));
        return hash;
    }

    /** The set a hash names, in file-name order, or empty when no row holds it. */
    public Optional<List<HookFile>> find(String hash) {
        Objects.requireNonNull(hash, "hash");
        List<String> found = ArchiveUnavailableException.translating("read a local hook set",
                () -> jdbc.query("SELECT files::text FROM local_hook_sets WHERE hash = ?",
                        (rs, row) -> rs.getString(1), hash));
        return found.isEmpty() ? Optional.empty() : Optional.of(parsed(hash, found.get(0)));
    }

    private static List<HookFile> parsed(String hash, String json) {
        try {
            List<HookFile> files = new ArrayList<>();
            for (JsonNode one : JSON.readTree(json)) {
                files.add(new HookFile(one.get("name").asText(), one.get("text").asText()));
            }
            return HookFile.ordered(files);
        } catch (Exception unreadable) {
            throw new IllegalStateException("the local hook set " + hash
                    + " could not be read back: " + unreadable.getMessage(), unreadable);
        }
    }
}
