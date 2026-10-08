package io.aeyer.plowshare.protocol;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Typed source deployment requests and retained receipts, shared by Java clients and the server.
 */
public final class ApplicationDeployment {
  private ApplicationDeployment() {}

  public record File(String path, String text) {}

  public record Deploy(
      String project,
      UUID requestId,
      UUID expectedRevision,
      FileStoreReference destination,
      List<FileStoreReference> writableAreas,
      List<File> files) {
    public Deploy {
      identity(project);
      Objects.requireNonNull(requestId);
      Objects.requireNonNull(destination);
      if (destination.path().isEmpty())
        throw new IllegalArgumentException("Deployment needs a dedicated destination");
      writableAreas = List.copyOf(writableAreas);
      files = List.copyOf(files);
      if (writableAreas.size() > 100 || new HashSet<>(writableAreas).size() != writableAreas.size())
        throw new IllegalArgumentException("Invalid deployment writable areas");
      validateFiles(files);
    }
  }

  public record Activate(String project, UUID requestId, UUID expectedRevision, UUID revision) {
    public Activate {
      identity(project);
      Objects.requireNonNull(requestId);
      Objects.requireNonNull(expectedRevision);
      Objects.requireNonNull(revision);
    }
  }

  public record Release(UUID revision, String digest, int fileCount) {
    public Release {
      Objects.requireNonNull(revision);
      if (digest == null || !digest.matches("[0-9a-f]{64}") || fileCount < 1 || fileCount > 128)
        throw new IllegalArgumentException("Invalid Application release");
    }
  }

  /** A retained receipt describes this mutation, not the project's current active revision. */
  public record Receipt(UUID requestId, String project, Release release) {
    public Receipt {
      Objects.requireNonNull(requestId);
      identity(project);
      Objects.requireNonNull(release);
    }
  }

  public record Status(
      String project,
      @com.fasterxml.jackson.annotation.JsonProperty(required = true) UUID activeRevision,
      List<Release> releases) {
    public Status {
      identity(project);
      releases = List.copyOf(releases);
      if (releases.size() > 100)
        throw new IllegalArgumentException("Too many Application releases");
    }
  }

  public static void validateFiles(List<File> files) {
    if (files == null || files.isEmpty() || files.size() > 128)
      throw new IllegalArgumentException("Application package needs 1–128 text files");
    Set<String> paths = new HashSet<>();
    int bytes = 0;
    for (var file : files) {
      if (file == null)
        throw new IllegalArgumentException("Application package files are required");
      String path = file.path();
      if (path == null
          || path.length() > 512
          || path.split("/").length > 17
          || !path.matches("[A-Za-z0-9_-][A-Za-z0-9_.-]*(?:/[A-Za-z0-9_-][A-Za-z0-9_.-]*)*")
          || Arrays.stream(path.split("/"))
              .anyMatch(
                  s ->
                      s.equals(".")
                          || s.equals("..")
                          || s.equals("node_modules")
                          || s.equals("build")
                          || s.equals("__pycache__"))
          || !paths.add(path.toLowerCase(Locale.ROOT)))
        throw new IllegalArgumentException(
            "Application package paths must be unique portable relative source paths");
      if (file.text() == null
          || file.text().indexOf('\0') >= 0
          || !StandardCharsets.UTF_8.newEncoder().canEncode(file.text())
          || file.text().getBytes(StandardCharsets.UTF_8).length > 65536)
        throw new IllegalArgumentException(
            "Application files must be UTF-8 text of at most 64 KiB without NUL");
      bytes += file.text().getBytes(StandardCharsets.UTF_8).length;
    }
    if (bytes > 131072 || !files.stream().anyMatch(file -> file.path().equals("plowshare.json")))
      throw new IllegalArgumentException(
          "Application package needs root plowshare.json and at most 128 KiB of text");
    for (String path : paths)
      for (String other : paths)
        if (other.startsWith(path + "/"))
          throw new IllegalArgumentException("Application package has a file/directory collision");
  }

  private static void identity(String project) {
    if (project == null
        || project.isBlank()
        || !project.equals(project.strip())
        || project.length() > 512
        || project.codePoints().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("Invalid Application project identity");
  }
}
