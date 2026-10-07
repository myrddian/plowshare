package io.aeyer.plowshare.protocol;

/** A portable location on the owning host; an empty path denotes the FileStore root. */
public record FileStoreReference(String store, String path) {
  public FileStoreReference {
    if (store == null || !store.matches("[a-z][a-z0-9_-]{0,63}"))
      throw new IllegalArgumentException("Invalid FileStore alias");
    if (path == null || path.length() > 2048 || path.contains("\\") || path.contains(":"))
      throw new IllegalArgumentException("FileStore paths must be portable relative paths");
    if (!path.isEmpty())
      for (String segment : path.split("/", -1))
        if (segment.isEmpty()
            || segment.equals(".")
            || segment.equals("..")
            || segment.equalsIgnoreCase(".git")
            || segment.codePoints().anyMatch(Character::isISOControl))
          throw new IllegalArgumentException(
              "FileStore paths cannot contain traversal or metadata");
  }
}
