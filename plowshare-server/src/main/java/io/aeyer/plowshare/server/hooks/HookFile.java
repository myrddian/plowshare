package io.aeyer.plowshare.server.hooks;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * One file of a person's local hooks, as their session served it: its name directly under {@code
 * .plowshare/hooks/} and its whole text (spec 2026-09-30-local-hooks-are-served).
 *
 * <p>The bounds live here because three places hold them to the same numbers: the reader that
 * refuses a set past them, the loader that retires a set no fire can still be using, and the
 * storage whose rows they size (decision 8: every number in a reply is one the sender picked).
 */
public record HookFile(String name, String text) {

  /** At most this many hook files in a set (decision 8). */
  public static final int MAX_FILES = 32;

  /** At most this many bytes in one file (decision 8). */
  public static final long MAX_FILE_BYTES = 256L * 1024;

  /** At most this many bytes in the whole set (decision 8). */
  public static final long MAX_SET_BYTES = 1024L * 1024;

  /** The hook a record names when no one file is to blame: the set could not be read or loaded. */
  public static final String WHOLE_SET = "(local hooks)";

  private static final ObjectMapper JSON = new ObjectMapper();

  public HookFile {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(text, "text");
  }

  /** {@code *.ts} or {@code *.js}, not a dotfile: {@code ProjectHookSet}'s rule for a directory. */
  public static boolean isHookName(String name) {
    return name != null && !name.startsWith(".") && (name.endsWith(".ts") || name.endsWith(".js"));
  }

  /** The set in file-name order: the order it is stored, hashed and run in. */
  public static List<HookFile> ordered(List<HookFile> files) {
    return files.stream().sorted(Comparator.comparing(HookFile::name)).toList();
  }

  /** {@code [{name, text}]} in file-name order: what {@link #hashOf} hashes and V73 stores. */
  public static String canonical(List<HookFile> files) {
    ArrayNode array = JSON.createArrayNode();
    for (HookFile file : ordered(files)) {
      ObjectNode one = array.addObject();
      one.put("name", file.name());
      one.put("text", file.text());
    }
    return array.toString();
  }

  /** {@code sha256:<hex>} over {@link #canonical}: equal sets share one row (spec §3). */
  public static String hashOf(List<HookFile> files) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(canonical(files).getBytes(StandardCharsets.UTF_8));
      return "sha256:" + HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException noSha256) {
      throw new IllegalStateException(
          "this runtime has no SHA-256, so no hook set can be named", noSha256);
    }
  }
}
