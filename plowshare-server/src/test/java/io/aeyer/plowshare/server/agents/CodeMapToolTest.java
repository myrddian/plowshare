package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.*;
import io.aeyer.plowshare.protocol.*;
import io.aeyer.plowshare.server.files.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodeMapToolTest {
  @TempDir Path temp;
  private static final ObjectMapper JSON = new ObjectMapper();

  private static String args(Map<String, Object> values) throws Exception {
    return JSON.writeValueAsString(values);
  }

  private static JsonNode hint(JsonNode response, String reason) {
    for (var hint : response.path("hints"))
      if (hint.path("reason").asText().equals(reason)) return hint;
    return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
  }

  @Test
  void suggestions_can_be_followed_and_reject_changed_sources() throws Exception {
    Path root = temp.toRealPath(),
        path =
            Files.writeString(
                root.resolve("A.java"),
                "// 😀\r\nclass A { void alpha() {} void another() {} }\r\n");
    var provider =
        LocalProvider.over(
            FileAccess.of(List.of(root), List.of()),
            List.of(new Grant(Scope.WORKSPACE, Mode.READ)));
    var tool =
        new CodeMapTool(
            new WorkspaceCodeMap(new ProviderRouter(home -> List.of(provider)), () -> false),
            new FileTools.Reads());
    var page =
        JSON.readTree(
            tool.run("{\"operation\":\"symbols\",\"query\":\"a\",\"limit\":1}", Home.global()));
    var next = hint(page, "next_page");
    assertEquals(CodeMapTool.NAME, next.path("tool").asText());
    var second = JSON.readTree(tool.run(next.path("arguments").toString(), Home.global()));
    assertEquals("alpha", second.path("results").get(0).path("name").asText());
    var read = hint(second, "read_declaration").path("arguments");
    var text = JSON.readTree(tool.run(read.toString(), Home.global()));
    assertEquals("void alpha() {}", text.path("text").asText());
    var continuation = hint(text, "continue_read").path("arguments");
    assertEquals(text.path("end").asInt(), continuation.path("offset").asInt());
    var continued = JSON.readTree(tool.run(continuation.toString(), Home.global()));
    assertEquals(" void another()", continued.path("text").asText());
    var last =
        JSON.readTree(
            tool.run(hint(continued, "continue_read").path("arguments").toString(), Home.global()));
    assertEquals(" {} }\n", last.path("text").asText());
    assertTrue(hint(last, "continue_read").isMissingNode());
    Files.writeString(path, "class A { void replaced() {} }");
    assertTrue(tool.run(read.toString(), Home.global()).contains("code changed"));
  }

  @Test
  void fallback_hints_require_actual_tools_and_only_name_displayed_files() throws Exception {
    Path root = temp.toRealPath();
    Files.writeString(root.resolve("A.java"), "class A {}");
    Path unsupported = Files.writeString(root.resolve("B.rs"), "fn unsupported() {}");
    var provider =
        LocalProvider.over(
            FileAccess.of(List.of(root), List.of()),
            List.of(new Grant(Scope.WORKSPACE, Mode.READ)));
    var reads = new FileTools.Reads();
    var tool =
        new CodeMapTool(
            new WorkspaceCodeMap(new ProviderRouter(home -> List.of(provider)), () -> false),
            reads);
    var first = JSON.readTree(tool.run("{\"operation\":\"files\",\"limit\":1}", Home.global()));
    assertTrue(
        hint(first, "source_fallback").isMissingNode(),
        "unshown unsupported files must not be exposed in hints");
    var page =
        JSON.readTree(
            tool.run("{\"operation\":\"files\",\"offset\":1,\"limit\":1}", Home.global()));
    assertFalse(hint(page, "source_fallback").has("tool"));
    reads.offered(Set.of(CodeMapTool.NAME, FileTools.READ_NAME, FileTools.GREP_NAME));
    page =
        JSON.readTree(
            tool.run("{\"operation\":\"files\",\"offset\":1,\"limit\":1}", Home.global()));
    var fallback = hint(page, "source_fallback");
    assertEquals(FileTools.READ_NAME, fallback.path("tool").asText());
    assertEquals(unsupported.toString(), fallback.path("arguments").path("path").asText());
    assertTrue(
        new FileTools.Read(new ProviderRouter(home -> List.of(provider)), reads)
            .run(fallback.path("arguments").toString(), Home.global())
            .contains("fn unsupported()"));
    var absent =
        JSON.readTree(
            tool.run(
                args(
                    Map.of(
                        "operation",
                        "symbols",
                        "path",
                        unsupported.toString(),
                        "query",
                        "unsupported")),
                Home.global()));
    var grep = hint(absent, "no_declarations");
    assertEquals(FileTools.GREP_NAME, grep.path("tool").asText());
    assertEquals(unsupported.toString(), grep.path("arguments").path("path").asText());
    assertTrue(
        new FileTools.Grep(new ProviderRouter(home -> List.of(provider)))
            .run(grep.path("arguments").toString(), Home.global())
            .contains("unsupported"));
    reads.offered(Set.of(CodeMapTool.NAME));
    absent =
        JSON.readTree(tool.run("{\"operation\":\"symbols\",\"query\":\"missing\"}", Home.global()));
    assertFalse(hint(absent, "no_declarations").has("tool"));
  }

  @Test
  void incomplete_coverage_is_a_hint_even_without_an_outline_or_any_files() throws Exception {
    var tool =
        new CodeMapTool(
            new WorkspaceCodeMap(new ProviderRouter(home -> List.of()), () -> false),
            new FileTools.Reads());
    var result =
        JSON.readTree(tool.run("{\"operation\":\"symbols\",\"query\":\"absent\"}", Home.global()));
    assertEquals("partial", result.path("state").asText());
    assertTrue(
        hint(result, "incomplete_coverage")
            .path("message")
            .asText()
            .contains("do not establish absence"));
    assertTrue(result.path("hints").size() <= 4);
  }

  @Test
  void live_symbols_page_and_exact_unicode_reads_are_hash_bound() throws Exception {
    Path root = temp.toRealPath(), path = root.resolve("A.java");
    String source = "\uFEFF// 😀\r\nclass A { void alpha() {} void another() {} }\r\n";
    Files.writeString(path, source);
    var provider =
        LocalProvider.over(
            FileAccess.of(List.of(root), List.of()),
            List.of(new Grant(Scope.WORKSPACE, Mode.READ)));
    var map = new WorkspaceCodeMap(new ProviderRouter(home -> List.of(provider)), () -> false);
    var reads = new FileTools.Reads(map);
    var tool = new CodeMapTool(map, reads);
    JsonNode page =
        JSON.readTree(
            tool.run(
                "{\"operation\":\"symbols\",\"query\":\"a\",\"offset\":1,\"limit\":1}",
                Home.global()));
    assertEquals(3, page.path("total").asInt());
    assertTrue(page.path("has_more").asBoolean());
    var symbol = page.path("results").get(0);
    assertEquals("alpha", symbol.path("name").asText());
    assertFalse(reads.seen(path));
    int start = symbol.path("start_offset").asInt(), end = symbol.path("end_offset").asInt();
    var request =
        Map.<String, Object>of(
            "operation",
            "read",
            "path",
            path.toString(),
            "source_hash",
            symbol.path("source_hash").asText(),
            "offset",
            start,
            "limit",
            end - start);
    JsonNode read = JSON.readTree(tool.run(args(request), Home.global()));
    assertEquals(
        source.substring(1).replace("\r\n", "\n").substring(start, end),
        read.path("text").asText());
    assertEquals("workspace_snapshot", read.path("source_kind").asText());
    assertTrue(reads.seen(path));
    Files.writeString(path, "class A { void beta() {} }");
    assertTrue(tool.run(args(request), Home.global()).contains("code changed"));
    assertTrue(
        tool.run("{\"operation\":\"files\",\"limit\":1.5}", Home.global())
            .startsWith("Code map refused"));
    assertTrue(
        tool.run("{\"operation\":\"files\",\"offset\":4294967296}", Home.global())
            .startsWith("Code map refused"));
  }

  @Test
  void file_tools_trigger_reconciliation_and_deletion_removes_the_current_path() throws Exception {
    Path root = temp.toRealPath(), path = Files.writeString(root.resolve("A.java"), "class A {}");
    var provider =
        spy(
            LocalProvider.over(
                FileAccess.of(List.of(root), List.of()),
                List.of(new Grant(Scope.WORKSPACE, Mode.WRITE))));
    var router = new ProviderRouter(home -> List.of(provider));
    var map = new WorkspaceCodeMap(router, () -> false);
    var reads = new FileTools.Reads(map);
    var tool = FileTools.of(CodeMapTool.NAME, router, reads);
    tool.run("{\"operation\":\"files\"}", Home.global());
    clearInvocations(provider);
    String changed =
        FileTools.of(FileTools.EDIT_NAME, router, reads)
            .run(
                args(Map.of("path", path.toString(), "old", "class A", "new", "class B")),
                Home.global());
    assertFalse(changed.contains("refused"), changed);
    verify(provider, atLeastOnce()).fingerprint(path);
    assertEquals(
        "B",
        JSON.readTree(
                tool.run(
                    args(Map.of("operation", "outline", "path", path.toString())), Home.global()))
            .path("results")
            .get(0)
            .path("name")
            .asText());
    FileTools.of(FileTools.DELETE_NAME, router, reads)
        .run(args(Map.of("path", path.toString())), Home.global());
    assertEquals(
        0,
        JSON.readTree(tool.run("{\"operation\":\"files\"}", Home.global())).path("total").asInt());
  }
}
