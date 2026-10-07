package io.aeyer.plowshare.server.files;

import io.aeyer.plowshare.protocol.FileStoreReference;
import java.util.List;

/** Bounded source access for an authorized server Application; no local client fallback. */
public interface ApplicationFiles {
  public record Caller(String project, String account, FileStoreReference location) {
    public Caller(String project, String account) {
      this(project, account, null);
    }
  }

  public record Entry(String path, String name, boolean directory) {}

  public record Listing(
      String project, String path, List<Entry> entries, boolean more, FileStoreReference location) {
    public Listing(String project, String path, List<Entry> entries, boolean more) {
      this(project, path, entries, more, null);
    }

    public Listing {
      entries = List.copyOf(entries);
    }
  }

  public record Document(
      String project,
      String path,
      String text,
      String revision,
      boolean writable,
      FileStoreReference location) {
    public Document(String project, String path, String text, String revision, boolean writable) {
      this(project, path, text, revision, writable, null);
    }
  }

  /** Lists one permitted directory. Linked and excluded entries are omitted. */
  Listing list(Caller caller, String path);

  /** Reads a regular UTF-8 file of at most 256 KiB and returns its content revision. */
  Document read(Caller caller, String path);

  /**
   * Replaces an existing text file only if its current content matches the reviewed revision.
   * Checks effective membership, writable areas and configuration authority afresh. A lost reply
   * must be reconciled with read, never automatically retried. Non-cooperating source owners can
   * change files independently; the filesystem is not a database transaction boundary.
   */
  Document save(Caller caller, String path, String text, String revision);
}
