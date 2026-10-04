package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.server.files.SessionChannel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Text packages through the existing server disk or client channel, never a client skill loader.
 */
public interface SkillSource extends DefinitionSource {
  String readResource(String skill, String relative);

  static String relative(String value) {
    if (value == null || value.isBlank())
      throw new IllegalArgumentException("resource path is required");
    Path path = Path.of(value);
    if (path.isAbsolute() || !path.normalize().toString().equals(value)) {
      throw new IllegalArgumentException("resource path must be relative to the skill package");
    }
    for (Path segment : path) {
      if (!ChannelDefinitions.isSafeSegment(segment.toString())
          || segment.toString().startsWith(".")) {
        throw new IllegalArgumentException("resource path contains an unsafe segment");
      }
    }
    return value;
  }

  static SkillSource disk(Path directory) {
    return new SkillSource() {
      @Override
      public String describe() {
        return directory.toString();
      }

      @Override
      public List<Definition> list() {
        if (!Files.exists(directory)) return List.of();
        if (!Files.isDirectory(directory))
          throw new IllegalArgumentException(directory + " is not a directory");
        try (var entries = Files.list(directory)) {
          List<Path> packages =
              entries
                  .filter(Files::isDirectory)
                  .filter(path -> !path.getFileName().toString().startsWith("."))
                  .sorted()
                  .limit(ChannelDefinitions.MAX_DEFINITIONS + 1L)
                  .toList();
          if (packages.size() > ChannelDefinitions.MAX_DEFINITIONS)
            throw new IllegalArgumentException("too many skill packages");
          List<Definition> found = new ArrayList<>();
          long bytes = 0;
          for (Path path : packages) {
            Path file = path.resolve("SKILL.md");
            if (!Files.exists(file)) continue;
            if (!path.toRealPath().startsWith(directory.toRealPath()))
              throw new IllegalArgumentException("skill leaves its tier");
            String text = bounded(file, path);
            bytes += text.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > ChannelDefinitions.MAX_SOURCE_BYTES)
              throw new IllegalArgumentException("skill tier exceeds source limit");
            found.add(
                new Definition(
                    path.getFileName().toString(), file.toAbsolutePath().toString(), text));
          }
          return List.copyOf(found);
        } catch (IOException failed) {
          throw new IllegalStateException("cannot list " + directory, failed);
        }
      }

      @Override
      public String readResource(String skill, String relative) {
        SkillSource.relative(skill);
        Path root = directory.resolve(skill);
        try {
          if (!root.toRealPath().startsWith(directory.toRealPath()))
            throw new IllegalArgumentException("skill leaves its tier");
        } catch (IOException failed) {
          throw new IllegalStateException("cannot resolve skill package", failed);
        }
        return bounded(root.resolve(SkillSource.relative(relative)), root);
      }
    };
  }

  static SkillSource channel(SessionChannel channel, String session) {
    ChannelDefinitions files = ChannelDefinitions.skills(channel, session);
    java.util.Map<String, String> packages = new java.util.LinkedHashMap<>();
    return new SkillSource() {
      @Override
      public String describe() {
        return files.describe();
      }

      @Override
      public List<Definition> list() {
        List<Definition> found = files.list();
        for (Definition file : found) {
          String path = file.origin().substring(("session " + session + ": ").length());
          if (!file.text().isEmpty())
            packages.put(file.name(), Path.of(path).getParent().toString());
        }
        return found;
      }

      @Override
      public String readResource(String skill, String relative) {
        SkillSource.relative(skill);
        String root = packages.get(skill);
        if (root == null)
          throw new IllegalArgumentException("skill was not discovered in this session");
        ChannelDefinitions.FileRead read =
            files.resource(root + "/" + SkillSource.relative(relative));
        if (read.text() == null)
          throw new IllegalArgumentException(
              read.absent() ? "resource does not exist" : read.unreadable());
        return read.text();
      }
    };
  }

  static SkillSource shipped() {
    return new SkillSource() {
      @Override
      public String describe() {
        return "shipped skills";
      }

      @Override
      public List<Definition> list() {
        try {
          Resource[] files =
              new PathMatchingResourcePatternResolver()
                  .getResources("classpath*:skills/*/SKILL.md");
          if (files.length > ChannelDefinitions.MAX_DEFINITIONS)
            throw new IllegalArgumentException("too many shipped skills");
          List<Definition> found = new ArrayList<>();
          long bytes = 0;
          for (Resource file : files) {
            String url = file.getURL().toString();
            String name = url.substring(url.lastIndexOf("/skills/") + 8, url.length() - 9);
            String text = resource(file);
            bytes += text.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > ChannelDefinitions.MAX_SOURCE_BYTES)
              throw new IllegalArgumentException("shipped skills exceed source limit");
            found.add(new Definition(name, url, text));
          }
          found.sort(Comparator.comparing(Definition::name));
          return List.copyOf(found);
        } catch (IOException failed) {
          throw new IllegalStateException("cannot list shipped skills", failed);
        }
      }

      @Override
      public String readResource(String skill, String relative) {
        Resource file =
            new PathMatchingResourcePatternResolver()
                .getResource(
                    "classpath:skills/"
                        + SkillSource.relative(skill)
                        + "/"
                        + SkillSource.relative(relative));
        return resource(file);
      }
    };
  }

  private static String bounded(Path file, Path root) {
    try {
      Path canonical = file.toRealPath();
      if (!canonical.startsWith(root.toRealPath()))
        throw new IllegalArgumentException("resource leaves its skill package");
      try (var stream = Files.newInputStream(canonical)) {
        return bytes(stream.readNBytes((int) ChannelDefinitions.MAX_DEFINITION_BYTES + 1));
      }
    } catch (IOException failed) {
      throw new IllegalStateException("cannot read " + file, failed);
    }
  }

  private static String resource(Resource file) {
    try (var stream = file.getInputStream()) {
      return bytes(stream.readNBytes((int) ChannelDefinitions.MAX_DEFINITION_BYTES + 1));
    } catch (IOException failed) {
      throw new IllegalStateException("cannot read " + file, failed);
    }
  }

  private static String bytes(byte[] bytes) {
    if (bytes.length > ChannelDefinitions.MAX_DEFINITION_BYTES)
      throw new IllegalArgumentException("skill file exceeds source limit");
    try {
      return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
    } catch (java.nio.charset.CharacterCodingException failed) {
      throw new IllegalArgumentException("skill file is not UTF-8", failed);
    }
  }
}
