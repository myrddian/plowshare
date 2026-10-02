package io.aeyer.plowshare.server.hooks.script;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@code Stripping} does not just remove types; it refuses TypeScript that is not
 * erasable, the same set Node's own {@code --experimental-strip-types} refuses. A
 * hook file that fails in Node must not silently load on the server.
 */
class StrippingTest {

    @Test
    void an_enum_is_refused() {
        HookFailure refused = assertThrows(HookFailure.class, () -> Stripping.javascript("10-x.ts",
                "enum A { B }\nexport default { name: 'x', stages: {} }\n"));
        assertTrue(refused.getMessage().contains("10-x.ts"), refused.getMessage());
        assertTrue(refused.getMessage().contains("enum"), refused.getMessage());
    }

    @Test
    void a_namespace_with_runtime_code_is_refused() {
        HookFailure refused = assertThrows(HookFailure.class, () -> Stripping.javascript("ns.ts",
                "namespace NS { export const x = 1 }\nexport default { name: 'x', stages: {} }\n"));
        assertTrue(refused.getMessage().contains("ns.ts"), refused.getMessage());
        assertTrue(refused.getMessage().contains("namespace"), refused.getMessage());
    }

    @Test
    void a_constructor_parameter_property_is_refused() {
        HookFailure refused = assertThrows(HookFailure.class, () -> Stripping.javascript("param.ts",
                "class C { constructor(private a: number) {} }\nexport default { name: 'x', stages: {} }\n"));
        assertTrue(refused.getMessage().contains("param.ts"), refused.getMessage());
        assertTrue(refused.getMessage().toLowerCase().contains("parameter propert"), refused.getMessage());
    }

    @Test
    void an_unused_value_import_is_refused() {
        // Unused-ness must not matter: swc4j's transpile silently elides an unused
        // value import, but the parse-time check runs before that elision, on the
        // declaration itself, so an import Node would refuse is refused here too.
        HookFailure refused = assertThrows(HookFailure.class, () -> Stripping.javascript("importer.ts",
                "import { helper } from './helper.ts'\nexport default { name: 'x', stages: {} }\n"));
        assertTrue(refused.getMessage().contains("importer.ts"), refused.getMessage());
        assertTrue(refused.getMessage().contains("import"), refused.getMessage());
    }

    @Test
    void a_used_value_import_is_also_refused() {
        HookFailure refused = assertThrows(HookFailure.class, () -> Stripping.javascript("importer2.ts",
                "import { helper } from './helper.ts'\nhelper()\nexport default { name: 'x', stages: {} }\n"));
        assertTrue(refused.getMessage().contains("import"), refused.getMessage());
    }

    @Test
    void a_type_only_import_is_accepted() {
        assertDoesNotThrow(() -> Stripping.javascript("ok1.ts",
                "import type { Hook } from '@plowshare/hooks'\nexport default { name: 'x', stages: {} }\n"));
    }

    @Test
    void an_inline_type_only_specifier_is_accepted() {
        assertDoesNotThrow(() -> Stripping.javascript("ok2.ts",
                "import { type Hook } from '@plowshare/hooks'\nexport default { name: 'x', stages: {} }\n"));
    }

    @Test
    void a_mixed_inline_import_with_a_value_specifier_is_refused() {
        HookFailure refused = assertThrows(HookFailure.class, () -> Stripping.javascript("mixed.ts",
                "import { type Hook, other } from './other.ts'\nother()\nexport default { name: 'x', stages: {} }\n"));
        assertTrue(refused.getMessage().contains("import"), refused.getMessage());
    }

    @Test
    void satisfies_is_accepted() {
        assertDoesNotThrow(() -> Stripping.javascript("ok3.ts",
                "interface Hook { name: string }\nconst h = { name: 'x' } satisfies Hook\nexport default h\n"));
    }

    @Test
    void an_interface_alone_is_accepted() {
        assertDoesNotThrow(() -> Stripping.javascript("ok4.ts",
                "interface Hook { name: string }\nexport default { name: 'x', stages: {} }\n"));
    }

    @Test
    void a_type_alias_is_accepted() {
        assertDoesNotThrow(() -> Stripping.javascript("ok5.ts",
                "type X = string\nexport default { name: 'x' as X, stages: {} }\n"));
    }

    @Test
    void an_ambient_declare_namespace_is_accepted() {
        // Ambient: no runtime code is generated for it, so it is erasable — the same
        // reason Node accepts `declare namespace` while refusing a real one.
        assertDoesNotThrow(() -> Stripping.javascript("ambient.ts",
                "declare namespace NS { const x: number }\nexport default { name: 'x', stages: {} }\n"));
    }

    // --- Fix round 2 ---

    @Test
    void an_import_equals_require_is_refused() {
        HookFailure refused = assertThrows(HookFailure.class, () -> Stripping.javascript("req.ts",
                "import x = require('y')\nexport default { name: 'x', stages: {} }\n"));
        assertTrue(refused.getMessage().contains("req.ts"), refused.getMessage());
        assertTrue(refused.getMessage().contains("import"), refused.getMessage());
    }

    @Test
    void an_exported_import_equals_is_refused() {
        // N is `declare`d (ambient) so this exercises only the import-equals check,
        // not the separate namespace-erasability one N would otherwise also trip.
        HookFailure refused = assertThrows(HookFailure.class, () -> Stripping.javascript("exp-eq.ts",
                "declare namespace N { const y: number }\nexport import Z = N.y\n"
                        + "export default { name: 'x', stages: {} }\n"));
        assertTrue(refused.getMessage().contains("import"), refused.getMessage());
    }

    @Test
    void a_type_only_import_equals_is_accepted() {
        assertDoesNotThrow(() -> Stripping.javascript("ok-eq.ts",
                "import type Z = require('y')\nexport default { name: 'x', stages: {} }\n"));
    }

    @Test
    void a_named_reexport_is_refused_as_an_import() {
        HookFailure refused = assertThrows(HookFailure.class, () -> Stripping.javascript("reexport.ts",
                "export { h } from './h'\nexport default { name: 'x', stages: {} }\n"));
        assertTrue(refused.getMessage().contains("import"), refused.getMessage());
    }

    @Test
    void an_export_star_is_refused_as_an_import() {
        HookFailure refused = assertThrows(HookFailure.class, () -> Stripping.javascript("star.ts",
                "export * from './h'\nexport default { name: 'x', stages: {} }\n"));
        assertTrue(refused.getMessage().contains("import"), refused.getMessage());
    }

    @Test
    void a_named_export_without_a_source_is_not_treated_as_an_import() {
        assertDoesNotThrow(() -> Stripping.javascript("no-src.ts",
                "const h = { name: 'x', stages: {} }\nexport { h }\nexport default h\n"));
    }

    @Test
    void an_export_assignment_is_refused_as_non_erasable() {
        HookFailure refused = assertThrows(HookFailure.class,
                () -> Stripping.javascript("cjs.ts", "const q = { name: 'x', stages: {} }\nexport = q\n"));
        assertTrue(refused.getMessage().contains("cjs.ts"), refused.getMessage());
        assertTrue(refused.getMessage().contains("export ="), refused.getMessage());
    }

    @Test
    void a_namespace_nested_inside_a_declare_namespace_is_accepted() {
        // The inner `namespace B` node does not itself carry `declare` — only the
        // outermost one does — so accepting it requires walking up to an ancestor,
        // not just checking the node's own flag.
        assertDoesNotThrow(() -> Stripping.javascript("nested-declare.ts",
                "declare namespace A { namespace B { const x: number } }\n"
                        + "export default { name: 'x', stages: {} }\n"));
    }

    @Test
    void a_namespace_with_only_type_declarations_is_accepted() {
        assertDoesNotThrow(() -> Stripping.javascript("types-only-ns.ts",
                "namespace T { export type X = string\n interface I { a: number } }\n"
                        + "export default { name: 'x', stages: {} }\n"));
    }

    @Test
    void a_namespace_with_a_nested_types_only_namespace_is_accepted() {
        assertDoesNotThrow(() -> Stripping.javascript("types-only-nested-ns.ts",
                "namespace T { namespace Inner { export type X = string } }\n"
                        + "export default { name: 'x', stages: {} }\n"));
    }

    @Test
    void a_namespace_with_a_declare_member_is_accepted() {
        assertDoesNotThrow(() -> Stripping.javascript("declare-member.ts",
                "namespace T { declare const x: number }\nexport default { name: 'x', stages: {} }\n"));
    }

    @Test
    void a_namespace_with_any_real_value_declaration_is_still_refused() {
        HookFailure refused = assertThrows(HookFailure.class, () -> Stripping.javascript("real-value-ns.ts",
                "namespace T { export type X = string\n export const y = 1 }\n"
                        + "export default { name: 'x', stages: {} }\n"));
        assertTrue(refused.getMessage().contains("namespace"), refused.getMessage());
    }

    /**
     * swc4j ships native libraries for a handful of platforms. Anywhere else its
     * first use is an {@link Error}, not an exception, and uncaught it would end
     * the turn instead of being a file that did not load.
     */
    @Test
    void a_platform_without_swc4j_is_a_file_that_did_not_load() {
        HookFailure refused = assertThrows(HookFailure.class, () -> Stripping.javascript("10-x.ts",
                "export default { name: 'x', stages: {} }\n",
                () -> {
                    throw new UnsatisfiedLinkError("no swc4j in java.library.path");
                }));
        assertTrue(refused.getMessage().contains("TypeScript hooks cannot be loaded on this server:"
                + " swc4j has no native library for this platform"), refused.getMessage());
    }
}
