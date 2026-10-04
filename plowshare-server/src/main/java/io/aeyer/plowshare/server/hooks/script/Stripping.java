package io.aeyer.plowshare.server.hooks.script;

import com.caoccao.javet.swc4j.Swc4j;
import com.caoccao.javet.swc4j.ast.interfaces.ISwc4jAst;
import com.caoccao.javet.swc4j.ast.interfaces.ISwc4jAstImportSpecifier;
import com.caoccao.javet.swc4j.ast.interfaces.ISwc4jAstModuleItem;
import com.caoccao.javet.swc4j.ast.interfaces.ISwc4jAstProgram;
import com.caoccao.javet.swc4j.ast.interfaces.ISwc4jAstTsNamespaceBody;
import com.caoccao.javet.swc4j.ast.module.Swc4jAstExportAll;
import com.caoccao.javet.swc4j.ast.module.Swc4jAstExportDecl;
import com.caoccao.javet.swc4j.ast.module.Swc4jAstImportDecl;
import com.caoccao.javet.swc4j.ast.module.Swc4jAstImportNamedSpecifier;
import com.caoccao.javet.swc4j.ast.module.Swc4jAstNamedExport;
import com.caoccao.javet.swc4j.ast.module.Swc4jAstTsExportAssignment;
import com.caoccao.javet.swc4j.ast.module.Swc4jAstTsImportEqualsDecl;
import com.caoccao.javet.swc4j.ast.module.Swc4jAstTsModuleBlock;
import com.caoccao.javet.swc4j.ast.stmt.Swc4jAstClassDecl;
import com.caoccao.javet.swc4j.ast.stmt.Swc4jAstFnDecl;
import com.caoccao.javet.swc4j.ast.stmt.Swc4jAstTsEnumDecl;
import com.caoccao.javet.swc4j.ast.stmt.Swc4jAstTsInterfaceDecl;
import com.caoccao.javet.swc4j.ast.stmt.Swc4jAstTsModuleDecl;
import com.caoccao.javet.swc4j.ast.stmt.Swc4jAstTsTypeAliasDecl;
import com.caoccao.javet.swc4j.ast.stmt.Swc4jAstVarDecl;
import com.caoccao.javet.swc4j.ast.ts.Swc4jAstTsParamProp;
import com.caoccao.javet.swc4j.enums.Swc4jMediaType;
import com.caoccao.javet.swc4j.enums.Swc4jSourceMapOption;
import com.caoccao.javet.swc4j.options.Swc4jParseOptions;
import com.caoccao.javet.swc4j.options.Swc4jTranspileOptions;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * A hook file as JavaScript: types stripped from {@code .ts}, {@code .js} as it is.
 *
 * <p>swc4j is SWC, the engine Node's own type stripping wraps, so a hook that strips in the TUI
 * strips the same here. Only erasable syntax is allowed on both sides; a file that needs code
 * generated from its types fails here with swc4j's own sentence.
 *
 * <p><b>"Erasable" is checked on the parse tree, before transpiling.</b> swc4j's transpile step is
 * a code generator, not a gate: it happily emits an enum's runtime object, a namespace's IIFE, or a
 * parameter property's field assignment, and it silently elides an unused value import rather than
 * refusing it — none of which Node's own {@code --experimental-strip-types} would accept. So a
 * {@code .ts} hook is parsed first (with {@code captureAst(true)}), walked for the four constructs
 * that need codegen, and refused if it has any — the same "TypeScript limited to erasable syntax" a
 * hook author sees on their own machine, not a surprise the server alone enforces.
 *
 * <p>One {@link Swc4j} for the process, created on first use: it costs about half a second to
 * initialise (measured, spec §7.1), which belongs to the first hook, not to server boot.
 */
public final class Stripping {

  private static volatile Swc4j swc4j;

  private Stripping() {}

  public static String javascript(String fileName, String source) throws HookFailure {
    return javascript(fileName, source, Stripping::swc4j);
  }

  /**
   * The same, with where the {@link Swc4j} comes from as a seam: a test has no platform without
   * one.
   */
  static String javascript(String fileName, String source, Supplier<Swc4j> stripper)
      throws HookFailure {
    if (!fileName.endsWith(".ts")) {
      return source;
    }
    try {
      URL specifier = URI.create("file:///" + fileName).toURL();
      Swc4j swc = stripper.get();
      checkErasable(swc, fileName, source, specifier);
      Swc4jTranspileOptions options =
          new Swc4jTranspileOptions()
              .setMediaType(Swc4jMediaType.TypeScript)
              .setSpecifier(specifier)
              .setSourceMap(Swc4jSourceMapOption.None);
      return swc.transpile(source, options).getCode();
    } catch (MalformedURLException impossible) {
      throw new HookFailure(fileName + " has a name that cannot be a module specifier", impossible);
    } catch (HookFailure refused) {
      throw refused;
    } catch (LinkageError unsupported) {
      // swc4j's native libraries exist for macOS (arm64, x86_64) and glibc
      // Linux (x86_64, arm64). Elsewhere — musl, Windows — the first use is an
      // UnsatisfiedLinkError, and every later one a NoClassDefFoundError: both
      // Errors, which nothing above this catches, so uncaught one ends the turn
      // instead of being a file that did not load. The platform, not the
      // error's message: that names library paths on this server.
      throw new HookFailure(
          fileName
              + ": TypeScript hooks cannot be loaded on this server: swc4j"
              + " has no native library for this platform ("
              + System.getProperty("os.name")
              + " "
              + System.getProperty("os.arch")
              + ")",
          unsupported);
    } catch (Exception unreadable) {
      throw new HookFailure(
          fileName
              + " could not be read as TypeScript that only needs its types"
              + " removed: "
              + unreadable.getMessage(),
          unreadable);
    }
  }

  private static void checkErasable(Swc4j swc, String fileName, String source, URL specifier)
      throws Exception {
    Swc4jParseOptions options =
        new Swc4jParseOptions()
            .setMediaType(Swc4jMediaType.TypeScript)
            .setSpecifier(specifier)
            .setCaptureAst(true);
    ISwc4jAstProgram<?> program = swc.parse(source, options).getProgram();
    if (!program.find(Swc4jAstTsEnumDecl.class).isEmpty()) {
      throw new HookFailure(
          "`"
              + fileName
              + "` uses an enum, which is not erasable TypeScript;"
              + " Node refuses it too — use a union of string literals");
    }
    for (Swc4jAstTsModuleDecl module : program.find(Swc4jAstTsModuleDecl.class)) {
      if (!isErasableNamespace(module)) {
        throw new HookFailure(
            "`"
                + fileName
                + "` uses a namespace, which is not erasable"
                + " TypeScript; Node refuses it too — use an object, or a separate module,"
                + " instead");
      }
    }
    if (!program.find(Swc4jAstTsParamProp.class).isEmpty()) {
      throw new HookFailure(
          "`"
              + fileName
              + "` uses a constructor parameter property, which is"
              + " not erasable TypeScript; Node refuses it too — declare the field and assign it"
              + " in the constructor body");
    }
    for (Swc4jAstImportDecl importDecl : program.find(Swc4jAstImportDecl.class)) {
      if (!isErasable(importDecl)) {
        throw importFailure(fileName);
      }
    }
    // `import x = require('y')` and `export import Z = N.y`: both bind a value
    // (a `require` call, or a property read on a namespace) and both generate a
    // `var` assignment at runtime — never erasable, except the rare `import
    // type X = require('y')` form, which is fully type-level and erased.
    for (Swc4jAstTsImportEqualsDecl importEquals : program.find(Swc4jAstTsImportEqualsDecl.class)) {
      if (!importEquals.isTypeOnly()) {
        throw importFailure(fileName);
      }
    }
    // A re-export resolves another module exactly like an import does — this
    // sandbox denies that resolution the same way, so it fails the same way
    // (see LoadedHook's IO-denial detection) but with a worse sentence if it is
    // not caught here first. `export type { X } from './x'` is a value import in
    // every practical sense too — the specifier still has to be resolved for
    // this build to check it is type-only in the source module — so it is
    // refused unconditionally, not exempted the way `import type` is above.
    for (Swc4jAstNamedExport namedExport : program.find(Swc4jAstNamedExport.class)) {
      if (namedExport.getSrc().isPresent()) {
        throw importFailure(fileName);
      }
    }
    if (!program.find(Swc4jAstExportAll.class).isEmpty()) {
      throw importFailure(fileName);
    }
    if (!program.find(Swc4jAstTsExportAssignment.class).isEmpty()) {
      throw new HookFailure(
          "`"
              + fileName
              + "` uses `export =`, which is not erasable TypeScript;"
              + " Node refuses it too — use `export default` instead");
    }
  }

  private static HookFailure importFailure(String fileName) {
    return new HookFailure(
        "`"
            + fileName
            + "` imports a value, which is not erasable"
            + " TypeScript; Node refuses it too — only `import type { X }` or"
            + " `import { type X }`, both erased, are possible");
  }

  /** An import is erasable only if every binding it introduces is type-only. */
  private static boolean isErasable(Swc4jAstImportDecl importDecl) {
    if (importDecl.isTypeOnly()) {
      return true;
    }
    for (ISwc4jAstImportSpecifier specifier : importDecl.getSpecifiers()) {
      if (!(specifier instanceof Swc4jAstImportNamedSpecifier named) || !named.isTypeOnly()) {
        return false;
      }
    }
    // No specifiers and not `import type`: either a bare side-effecting import
    // (`import './x'`), which runs code and is never erasable, or an import with
    // no bindings at all — neither is something `import type` can express, so
    // there is nothing to accept if it reaches this line.
    return !importDecl.getSpecifiers().isEmpty();
  }

  /**
   * A namespace is erasable in two cases: it (or an enclosing namespace) is {@code declare}, so it
   * is ambient and generates nothing at all; or its own body, wherever it is not ambient, holds
   * only type-level declarations that generate nothing either.
   */
  private static boolean isErasableNamespace(Swc4jAstTsModuleDecl module) {
    return hasDeclareAncestorOrSelf(module) || isBodyTypeOnly(module);
  }

  /**
   * {@code isDeclare()} is set only on the namespace statement that actually wrote {@code declare}
   * — {@code declare namespace A { namespace B {} } }` leaves {@code B}'s own flag false — so
   * ambience has to be checked by walking up through enclosing namespaces, not read off one node.
   */
  private static boolean hasDeclareAncestorOrSelf(Swc4jAstTsModuleDecl module) {
    Swc4jAstTsModuleDecl current = module;
    while (current != null) {
      if (current.isDeclare()) {
        return true;
      }
      current = current.getParent(Swc4jAstTsModuleDecl.class).orElse(null);
    }
    return false;
  }

  private static boolean isBodyTypeOnly(Swc4jAstTsModuleDecl module) {
    Optional<ISwc4jAstTsNamespaceBody> body = module.getBody();
    if (body.isEmpty()) {
      return true;
    }
    ISwc4jAstTsNamespaceBody namespaceBody = body.get();
    if (namespaceBody instanceof Swc4jAstTsModuleDecl dotted) {
      // The `namespace A.B { ... }` dotted form: B is itself the body.
      return isErasableNamespace(dotted);
    }
    if (namespaceBody instanceof Swc4jAstTsModuleBlock block) {
      for (ISwc4jAstModuleItem item : block.getBody()) {
        if (!isTypeOnlyItem(item)) {
          return false;
        }
      }
      return true;
    }
    return false;
  }

  /**
   * A type alias or interface is always erasable; a nested namespace is erasable by the same
   * two-case rule as any other; anything else — a variable, function, class or enum — is erasable
   * only if that member itself carries {@code declare}, which is legal even inside a non-ambient
   * namespace and marks just that one member as generating nothing.
   */
  private static boolean isTypeOnlyItem(ISwc4jAstModuleItem item) {
    ISwc4jAst real = item instanceof Swc4jAstExportDecl exported ? exported.getDecl() : item;
    if (real instanceof Swc4jAstTsTypeAliasDecl || real instanceof Swc4jAstTsInterfaceDecl) {
      return true;
    }
    if (real instanceof Swc4jAstTsModuleDecl nested) {
      return isErasableNamespace(nested);
    }
    if (real instanceof Swc4jAstVarDecl varDecl) {
      return varDecl.isDeclare();
    }
    if (real instanceof Swc4jAstClassDecl classDecl) {
      return classDecl.isDeclare();
    }
    if (real instanceof Swc4jAstFnDecl fnDecl) {
      return fnDecl.isDeclare();
    }
    if (real instanceof Swc4jAstTsEnumDecl enumDecl) {
      return enumDecl.isDeclare();
    }
    return false;
  }

  private static Swc4j swc4j() {
    Swc4j made = swc4j;
    if (made == null) {
      synchronized (Stripping.class) {
        made = swc4j;
        if (made == null) {
          made = new Swc4j();
          swc4j = made;
        }
      }
    }
    return made;
  }
}
