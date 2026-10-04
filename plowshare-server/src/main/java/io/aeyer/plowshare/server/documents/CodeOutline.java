package io.aeyer.plowshare.server.documents;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.treesitter.*;

/** A bounded syntax projection. Names are declarations, never resolved references or calls. */
public record CodeOutline(
    String status,
    String reason,
    String language,
    String parser,
    String sourceHash,
    List<Symbol> symbols) {
  public static final String VERSION =
      "declarations-v1:tree-sitter-0.26.6:java-0.23.5:js-python-0.25.0:ts-tsx-0.23.2";
  public static final int MAX_SOURCE_CHARS = 1_048_576;
  private static final int MAX_NODES = 200_000, MAX_SYMBOLS = 10_000;

  public CodeOutline {
    symbols = List.copyOf(symbols);
  }

  public record Symbol(
      int ordinal,
      String name,
      String kind,
      String qualifiedName,
      Integer parentOrdinal,
      String signature,
      int start,
      int end,
      int startLine,
      int endLine) {}

  private record Visit(TSNode node, Symbol parent) {}

  private record Declaration(TSNode name, TSNode body, String kind) {}

  public static CodeOutline parse(String source, String language, String sourceName) {
    String hash = Derivation.sha256(source);
    if (!List.of("java", "javascript", "typescript", "python").contains(language))
      return new CodeOutline(
          "unsupported", "language_not_supported", language, VERSION, hash, List.of());
    if (source.length() > MAX_SOURCE_CHARS)
      return new CodeOutline("limited", "source_size_limit", language, VERSION, hash, List.of());
    // The raw-reader API avoids JNI modified UTF-8. Native UTF-16LE bytes / 2
    // are exactly the retained extraction's UTF-16 coordinates, on any host.
    byte[] bytes = source.getBytes(StandardCharsets.UTF_16LE);
    long deadline = System.nanoTime() + 2_000_000_000L;
    try (TSParser parser = new TSParser()) {
      TSLanguage grammar =
          switch (language) {
            case "java" -> new TreeSitterJava();
            case "python" -> new TreeSitterPython();
            case "javascript" -> new TreeSitterJavascript();
            default ->
                sourceName.toLowerCase(Locale.ROOT).endsWith(".tsx")
                    ? new TreeSitterTsx()
                    : new TreeSitterTypescript();
          };
      if (!parser.setLanguage(grammar))
        return new CodeOutline(
            "failed", "grammar_abi_mismatch", language, VERSION, hash, List.of());
      try (TSTree tree =
          parser.parseWithOptions(
              new byte[8192],
              null,
              (buffer, offset, position) -> {
                int count = Math.min(buffer.length, bytes.length - offset);
                if (count <= 0) return 0;
                System.arraycopy(bytes, offset, buffer, 0, count);
                return count;
              },
              TSInputEncoding.TSInputEncodingUTF16LE,
              state -> System.nanoTime() >= deadline || Thread.currentThread().isInterrupted())) {
        if (tree == null)
          return new CodeOutline("limited", "parse_cancelled", language, VERSION, hash, List.of());
        TSNode root = tree.getRootNode();
        List<Symbol> found = new ArrayList<>();
        int[] lineStarts = lineStarts(source);
        ArrayDeque<Visit> pending = new ArrayDeque<>();
        pending.push(new Visit(root, null));
        int visited = 0;
        boolean limited = false;
        while (!pending.isEmpty()) {
          if (++visited > MAX_NODES
              || found.size() >= MAX_SYMBOLS
              || System.nanoTime() >= deadline
              || Thread.currentThread().isInterrupted()) {
            limited = true;
            break;
          }
          Visit visit = pending.pop();
          TSNode node = visit.node();
          Symbol parent = visit.parent();
          Declaration declaration = declaration(node, language, parent);
          if (declaration != null
              && !declaration.name().isNull()
              && !declaration.name().isMissing()
              && !declaration.name().hasError()) {
            int start = node.getStartByte() / 2, end = node.getEndByte() / 2;
            int nameStart = declaration.name().getStartByte() / 2,
                nameEnd = declaration.name().getEndByte() / 2;
            if (start >= 0 && end <= source.length() && end > start && nameEnd > nameStart) {
              if (nameEnd - nameStart > 512
                  || (parent != null
                      && parent.qualifiedName().length() + nameEnd - nameStart > 2048)) {
                limited = true;
                continue;
              }
              String name = source.substring(nameStart, nameEnd);
              int signatureEnd =
                  declaration.body().isNull() ? end : declaration.body().getStartByte() / 2;
              signatureEnd = Math.min(Math.max(start, signatureEnd), start + 512);
              if (signatureEnd < source.length()
                  && signatureEnd > start
                  && Character.isHighSurrogate(source.charAt(signatureEnd - 1))) signatureEnd--;
              String signature =
                  source.substring(start, signatureEnd).replaceAll("\\s+", " ").strip();
              Symbol symbol =
                  new Symbol(
                      found.size() + 1,
                      name,
                      declaration.kind(),
                      parent == null ? name : parent.qualifiedName() + "." + name,
                      parent == null ? null : parent.ordinal(),
                      signature,
                      start,
                      end,
                      lineAt(lineStarts, start),
                      lineAt(lineStarts, end - 1));
              found.add(symbol);
              parent = symbol;
            }
          }
          for (int i = node.getNamedChildCount() - 1; i >= 0; i--)
            pending.push(new Visit(node.getNamedChild(i), parent));
        }
        return new CodeOutline(
            limited ? "limited" : root.hasError() ? "partial" : "ready",
            limited ? "outline_limit" : root.hasError() ? "syntax_errors" : null,
            language,
            VERSION,
            hash,
            found);
      }
    } catch (LinkageError | RuntimeException unavailable) {
      // Preserve source/chunks even when a grammar/native is unavailable;
      // the durable outline status makes the missing capability explicit.
      return new CodeOutline("failed", "parser_unavailable", language, VERSION, hash, List.of());
    }
  }

  private static int[] lineStarts(String source) {
    var starts = new java.util.ArrayList<Integer>();
    starts.add(0);
    for (int i = 0; i < source.length(); i++) if (source.charAt(i) == '\n') starts.add(i + 1);
    return starts.stream().mapToInt(Integer::intValue).toArray();
  }

  private static int lineAt(int[] starts, int at) {
    int found = java.util.Arrays.binarySearch(starts, at);
    return found >= 0 ? found + 1 : -found - 1;
  }

  private static Declaration declaration(TSNode node, String language, Symbol parent) {
    String kind =
        switch (node.getType()) {
          case "class_declaration", "abstract_class_declaration", "class_definition" -> "class";
          case "interface_declaration" -> "interface";
          case "enum_declaration" -> "enum";
          case "record_declaration" -> "record";
          case "annotation_type_declaration" -> "annotation";
          case "type_alias_declaration" -> "type";
          case "constructor_declaration", "compact_constructor_declaration" -> "constructor";
          case "method_declaration",
                  "method_definition",
                  "method_signature",
                  "abstract_method_signature" ->
              "method";
          case "function_declaration", "generator_function_declaration", "function_signature" ->
              "function";
          case "function_definition" ->
              parent != null && parent.kind().equals("class") ? "method" : "function";
          default -> null;
        };
    if (kind != null)
      return new Declaration(
          node.getChildByFieldName("name"), node.getChildByFieldName("body"), kind);
    if (!language.equals("java") && node.getType().equals("variable_declarator")) {
      TSNode value = node.getChildByFieldName("value"), name = node.getChildByFieldName("name");
      if (!value.isNull()
          && !name.isNull()
          && name.getType().equals("identifier")
          && List.of("arrow_function", "function_expression", "generator_function")
              .contains(value.getType()))
        return new Declaration(name, value.getChildByFieldName("body"), "function");
    }
    return null;
  }
}
