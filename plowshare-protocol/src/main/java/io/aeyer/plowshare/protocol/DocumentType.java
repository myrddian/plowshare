package io.aeyer.plowshare.protocol;

import java.util.Locale;
import java.util.Map;

/** Document routing metadata. Language is inferred from the source name, not semantic analysis. */
public record DocumentType(String type, String subtype) {
  private static final Map<String, String> LANGUAGES =
      Map.ofEntries(
          Map.entry("java", "java"),
          Map.entry("kt", "kotlin"),
          Map.entry("kts", "kotlin"),
          Map.entry("js", "javascript"),
          Map.entry("mjs", "javascript"),
          Map.entry("cjs", "javascript"),
          Map.entry("jsx", "javascript"),
          Map.entry("ts", "typescript"),
          Map.entry("tsx", "typescript"),
          Map.entry("py", "python"),
          Map.entry("rs", "rust"),
          Map.entry("go", "go"),
          Map.entry("c", "c"),
          Map.entry("h", "c"),
          Map.entry("cc", "cpp"),
          Map.entry("cpp", "cpp"),
          Map.entry("hpp", "cpp"),
          Map.entry("cs", "csharp"),
          Map.entry("rb", "ruby"),
          Map.entry("swift", "swift"),
          Map.entry("sh", "shell"),
          Map.entry("bash", "shell"),
          Map.entry("zsh", "shell"),
          Map.entry("sql", "sql"),
          Map.entry("scala", "scala"),
          Map.entry("php", "php"),
          Map.entry("lua", "lua"));

  public DocumentType {
    if (!"document".equals(type) && !"code".equals(type))
      throw new IllegalArgumentException("document type must be document or code");
    if (subtype == null || !subtype.matches("[a-z][a-z0-9_]*"))
      throw new IllegalArgumentException("document subtype must be a nonblank routing identifier");
  }

  public boolean isCode() {
    return "code".equals(type);
  }

  public String corpus() {
    return isCode() ? "code" : "documents";
  }

  public static DocumentType classify(String sourceName, String mediaType) {
    String media =
        mediaType == null ? "" : mediaType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
    if (media.equals("application/pdf")) return new DocumentType("document", "pdf");
    if (media.equals("text/html")) return new DocumentType("document", "html");
    String name = sourceName == null ? "" : sourceName.replace('\\', '/');
    name = name.substring(name.lastIndexOf('/') + 1);
    int dot = name.lastIndexOf('.');
    String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    String language = LANGUAGES.get(extension);
    if (language != null) return new DocumentType("code", language);
    String subtype =
        switch (extension) {
          case "md", "markdown" -> "markdown";
          case "pdf" -> "pdf";
          case "html", "htm" -> "html";
          default -> "text";
        };
    return new DocumentType("document", subtype);
  }
}
