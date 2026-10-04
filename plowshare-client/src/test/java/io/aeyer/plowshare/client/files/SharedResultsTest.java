package io.aeyer.plowshare.client.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aeyer.plowshare.protocol.FileReply;
import io.aeyer.plowshare.protocol.FileRequest;
import io.aeyer.plowshare.protocol.FileResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

/**
 * The same changes asked of this client and of the terminal client, answered with the same facts.
 *
 * <p>{@code plowshare-protocol}'s {@code file-results.json} is a table of disks, requests and the
 * {@link FileReply} each must produce — outcome and {@link FileResult} (and, for an answered read
 * or stat, its span), and never a sentence: changes, reads, stats, globs, searches and a run's
 * fence alike. This runs it against {@link ClientEnforcer}; the TUI's {@code enforcer.test.ts} runs
 * the same file against its enforcer. The server words whatever either sends, so a fact that one
 * client reports and the other does not would be a model told two things about one disk — which is
 * what the table is for.
 *
 * <p>{@code {root}} and {@code {outside}} in the table are this case's workspace and a directory
 * beside it, both as the disk spells them, so a path in a result is the path the request named.
 */
class SharedResultsTest {

  private static final ObjectMapper JSON =
      new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

  private static final Path TABLE =
      Path.of(
          "..",
          "plowshare-protocol",
          "src",
          "test",
          "resources",
          "io",
          "aeyer",
          "plowshare",
          "protocol",
          "file-results.json");

  @TempDir Path tmp;

  @TestFactory
  List<DynamicTest> every_change_in_the_shared_table() throws Exception {
    JsonNode cases = JSON.readTree(Files.readString(TABLE));
    assertTrue(cases.size() > 20, "the table has the cases it was written with");
    List<DynamicTest> tests = new ArrayList<>();
    int n = 0;
    for (JsonNode c : cases) {
      int index = n++;
      tests.add(DynamicTest.dynamicTest(c.get("name").asText(), () -> run(c, index)));
    }
    return tests;
  }

  private void run(JsonNode c, int index) throws Exception {
    Path root = Files.createDirectories(tmp.resolve("case-" + index + "/repo")).toRealPath();
    Path outside = Files.createDirectories(tmp.resolve("case-" + index + "/other")).toRealPath();
    lay(c, root);
    Workspace workspace = new Workspace();
    workspace.set(List.of(root));

    // Bound as the wire binds it, so every field a case names reaches the
    // enforcer — a read's window, a glob's pattern, a search's needle.
    ObjectNode asked =
        (ObjectNode) JSON.readTree(spelled(c.get("request").toString(), root, outside));
    asked.put("id", "r" + index);
    FileRequest request = JSON.treeToValue(asked, FileRequest.class);

    FileReply reply = new ClientEnforcer(workspace).answer(request);

    JsonNode expected = c.get("reply");
    assertEquals(expected.get("outcome").asText(), reply.outcome(), String.valueOf(reply));
    assertNull(reply.sentence(), "a file action is answered with facts, never words");
    FileResult result =
        expected.has("result")
            ? JSON.readValue(
                spelled(expected.get("result").toString(), root, outside), FileResult.class)
            : null;
    assertEquals(result, reply.result());
    if (expected.has("span")) {
      assertEquals(expected.get("span"), JSON.valueToTree(reply.span()));
    }
    if (c.has("after")) {
      Iterator<Map.Entry<String, JsonNode>> after = c.get("after").fields();
      while (after.hasNext()) {
        Map.Entry<String, JsonNode> file = after.next();
        Path at = root.resolve(file.getKey());
        if (file.getValue().isNull()) {
          assertFalse(Files.exists(at, LinkOption.NOFOLLOW_LINKS), file.getKey());
        } else {
          assertEquals(file.getValue().asText(), Files.readString(at), file.getKey());
        }
      }
    }
  }

  /** The case's disk: text files, directories, raw bytes and links, under the root. */
  private static void lay(JsonNode c, Path root) throws Exception {
    for (JsonNode dir : iterable(c.get("dirs"))) {
      Files.createDirectories(root.resolve(dir.asText()));
    }
    fields(
        c.get("files"),
        (name, value) -> {
          Path at = root.resolve(name);
          Files.createDirectories(at.getParent());
          Files.writeString(at, value.asText());
        });
    fields(
        c.get("bytes"),
        (name, value) -> {
          byte[] bytes = new byte[value.size()];
          for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) value.get(i).asInt();
          }
          Files.write(root.resolve(name), bytes);
        });
    fields(
        c.get("links"),
        (name, value) ->
            Files.createSymbolicLink(root.resolve(name), root.resolve(value.asText())));
  }

  private interface Entry {
    void accept(String name, JsonNode value) throws Exception;
  }

  private static void fields(JsonNode node, Entry each) throws Exception {
    if (node == null) {
      return;
    }
    Iterator<Map.Entry<String, JsonNode>> all = node.fields();
    while (all.hasNext()) {
      Map.Entry<String, JsonNode> one = all.next();
      each.accept(one.getKey(), one.getValue());
    }
  }

  private static Iterable<JsonNode> iterable(JsonNode node) {
    return node == null ? List.of() : node;
  }

  private static String spelled(String text, Path root, Path outside) {
    return text.replace("{root}", root.toString()).replace("{outside}", outside.toString());
  }
}
