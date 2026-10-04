package io.aeyer.plowshare.server.personal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Editable starter copies. Initialization calls this once; subsequent startup never reinstalls
 * them.
 */
public final class PersonalStarter {
  private PersonalStarter() {}

  public record Installed(List<String> created, List<String> preserved) {
    public Installed {
      created = List.copyOf(created);
      preserved = List.copyOf(preserved);
    }
  }

  public static List<String> files() throws IOException {
    try (InputStream manifest = resource("manifest.txt")) {
      List<String> files =
          new String(manifest.readAllBytes(), StandardCharsets.UTF_8)
              .lines()
              .filter(line -> !line.isBlank())
              .toList();
      if (files.size() != files.stream().distinct().count())
        throw new IOException("Duplicate Personal starter path");
      for (String file : files) {
        Path relative = Path.of(file);
        if (relative.isAbsolute()
            || !relative.normalize().toString().equals(file)
            || !file.startsWith("Resources/") && !file.startsWith("Planning/")) {
          throw new IOException("Invalid Personal starter path: " + file);
        }
      }
      return files;
    }
  }

  public static Installed install(Path directory) throws IOException {
    Files.createDirectories(directory);
    if (Files.isSymbolicLink(directory))
      throw new IOException("Personal starter root must not be a symlink");
    Path root = directory.toRealPath();
    List<String> created = new ArrayList<>(), preserved = new ArrayList<>();
    for (String relative : files()) {
      Path target = root.resolve(relative);
      Path current = root;
      for (Path segment : root.relativize(target)) {
        current = current.resolve(segment);
        if (Files.isSymbolicLink(current))
          throw new IOException("Personal starter path must not be a symlink: " + relative);
      }
      if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
        if (!Files.isRegularFile(target))
          throw new IOException("Personal starter target must be a file: " + relative);
        preserved.add(relative);
        continue;
      }
      Files.createDirectories(target.getParent());
      try (InputStream source = resource(relative)) {
        try {
          Files.copy(source, target);
          created.add(relative);
        } catch (FileAlreadyExistsException raced) {
          preserved.add(relative);
        }
      }
    }
    return new Installed(created, preserved);
  }

  private static InputStream resource(String name) throws IOException {
    InputStream source =
        PersonalStarter.class.getClassLoader().getResourceAsStream("personal-starter/" + name);
    if (source == null) throw new IOException("Missing Personal starter resource: " + name);
    return source;
  }
}
