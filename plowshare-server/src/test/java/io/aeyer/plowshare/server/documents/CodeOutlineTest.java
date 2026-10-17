package io.aeyer.plowshare.server.documents;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class CodeOutlineTest {
  @Test
  void coldParserLoadingDoesNotConsumeTheDocumentParseBudget() throws Exception {
    // A fresh document-package loader repeats dependency resolution even if another test has
    // warmed the parent's native libraries. The delay models cold native extraction/loading.
    try (var loader = new SlowParserLoader()) {
      Class<?> outlineType = loader.loadClass(CodeOutline.class.getName());
      var outline =
          outlineType
              .getMethod("parse", String.class, String.class, String.class)
              .invoke(
                  null, "// 😀\nclass A { void alpha() {} void another() {} }\n", "java", "A.java");
      assertTrue(loader.delayed, "the cold-loading path must have been exercised");
      assertEquals("ready", outlineType.getMethod("status").invoke(outline), outline::toString);
      List<?> symbols =
          assertInstanceOf(List.class, outlineType.getMethod("symbols").invoke(outline));
      assertEquals(3, symbols.size(), outline::toString);
    }
  }

  @Test
  void anInterruptedParseStillRefusesAnUnboundedOutline() {
    try {
      Thread.currentThread().interrupt();
      var outline = CodeOutline.parse("class A { void alpha() {} }", "java", "A.java");
      assertEquals("limited", outline.status());
      assertTrue(outline.symbols().isEmpty());
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
  }

  private static final class SlowParserLoader extends URLClassLoader {
    private boolean delayed;

    SlowParserLoader() {
      super(
          new URL[] {CodeOutline.class.getProtectionDomain().getCodeSource().getLocation()},
          CodeOutline.class.getClassLoader());
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      synchronized (getClassLoadingLock(name)) {
        if (name.equals("org.treesitter.TSParser") && !delayed) {
          delayed = true;
          try {
            TimeUnit.MILLISECONDS.sleep(2200);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ClassNotFoundException(name, interrupted);
          }
        }
        // Derivation.sha256 is package-private: load the document package together so this
        // remains the same runtime package, while native bindings stay in the parent loader.
        if (name.startsWith(CodeOutline.class.getPackageName() + ".")) {
          Class<?> found = findLoadedClass(name);
          if (found == null) found = findClass(name);
          if (resolve) resolveClass(found);
          return found;
        }
        return super.loadClass(name, resolve);
      }
    }
  }

  @Test
  void java_declarations_ignore_comments_strings_and_pin_unicode_coordinates() {
    String source =
        "// 😀 class Fake {}\nclass Real {\n  String text = \"void fake() {}\";\n  Real() {}\n  void run() { System.out.println(\"😀\"); }\n  class Inner { void run() {} }\n}\n";
    var outline = CodeOutline.parse(source, "java", "Real.java");
    assertEquals("ready", outline.status(), outline.reason());
    assertEquals(
        List.of("Real", "Real.Real", "Real.run", "Real.Inner", "Real.Inner.run"),
        outline.symbols().stream().map(CodeOutline.Symbol::qualifiedName).toList());
    var run = outline.symbols().get(2);
    assertEquals(
        "void run() { System.out.println(\"😀\"); }", source.substring(run.start(), run.end()));
    assertEquals(5, run.startLine());
    assertEquals(5, run.endLine());
    assertEquals(1, run.parentOrdinal());
    assertEquals("void run()", run.signature());
  }

  @Test
  void typescript_named_arrows_methods_types_and_tsx_use_real_grammars() {
    String source =
        "// 😀 function fake() {}\nexport interface Store { get(id: string): string; }\nexport type ID = string;\nexport const load = (id: ID) => id;\nexport class Service { get(id: ID) { return load(id); } }\n";
    var outline = CodeOutline.parse(source, "typescript", "service.ts");
    assertEquals("ready", outline.status(), outline.reason());
    assertEquals(
        List.of("Store", "Store.get", "ID", "load", "Service", "Service.get"),
        outline.symbols().stream().map(CodeOutline.Symbol::qualifiedName).toList());
    var load = outline.symbols().get(3);
    assertEquals("load = (id: ID) => id", source.substring(load.start(), load.end()));
    var tsx =
        CodeOutline.parse("export const View = () => <div>😀</div>;", "typescript", "view.tsx");
    assertEquals("ready", tsx.status(), tsx.reason());
    assertEquals("View", tsx.symbols().getFirst().name());
  }

  @Test
  void javascript_and_python_support_nested_declarations() {
    var js =
        CodeOutline.parse(
            "export function load() {}\nconst save = function () {};\nclass Store { read() {} }",
            "javascript",
            "store.js");
    assertEquals("ready", js.status(), js.reason());
    assertEquals(
        List.of("load", "save", "Store", "Store.read"),
        js.symbols().stream().map(CodeOutline.Symbol::qualifiedName).toList());
    String py =
        "# 😀 def fake():\nclass Store:\n    def read(self):\n        def nested():\n            return '😀'\n        return nested()\n\ndef load():\n    return Store()\n";
    var python = CodeOutline.parse(py, "python", "store.py");
    assertEquals("ready", python.status(), python.reason());
    assertEquals(
        List.of("Store", "Store.read", "Store.read.nested", "load"),
        python.symbols().stream().map(CodeOutline.Symbol::qualifiedName).toList());
    assertEquals("method", python.symbols().get(1).kind());
    assertEquals("function", python.symbols().get(2).kind());
    for (var symbol : python.symbols())
      assertTrue(py.substring(symbol.start(), symbol.end()).contains(symbol.name()));
  }

  @Test
  void error_recovery_empty_unsupported_and_bounds_are_explicit() {
    assertEquals("ready", CodeOutline.parse("// no declarations", "java", "empty.java").status());
    assertEquals(
        "partial",
        CodeOutline.parse("class Broken { void okay() {} void broken( }", "java", "broken.java")
            .status());
    var unsupported = CodeOutline.parse("fn run() {}", "rust", "main.rs");
    assertEquals("unsupported", unsupported.status());
    assertTrue(unsupported.symbols().isEmpty());
    assertEquals(
        "limited",
        CodeOutline.parse(" ".repeat(CodeOutline.MAX_SOURCE_CHARS + 1), "java", "large.java")
            .status());
    var largeName = CodeOutline.parse("class " + "X".repeat(513) + " {}", "java", "name.java");
    assertEquals("limited", largeName.status());
    assertTrue(largeName.symbols().isEmpty());
  }
}
