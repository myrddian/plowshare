package io.aeyer.plowshare.server.agents;

/**
 * {@link DefinitionWriter#write} refused a definition for the one reason that
 * is not about the definition itself: a file already sits at that name and the
 * caller did not ask to replace it.
 *
 * <p>Its own type, unlike every other refusal {@link DefinitionWriter#write}
 * raises — those are {@link io.aeyer.plowshare.server.faults.CallerFault}
 * directly, and this one is kept apart from that type rather than folded into
 * it because the two answer different statuses and no caller can tell them
 * apart by parsing a sentence. {@code faults.Faults} holds that difference
 * once, mapping this type to {@code 409} and {@code CallerFault} to {@code
 * 400}, so every surface gets it — {@code api.AgentController.define} used to
 * catch this type by name and restate it, and no longer needs to.
 *
 * <p><b>A subtype of {@link IllegalArgumentException}, which {@code Faults}
 * deliberately has no row for.</b> The 409 above is therefore reached only
 * because {@code Faults.of} matches a thrown class before any of its ancestors;
 * {@code
 * FaultsTest.a_definition_that_already_exists_is_a_conflict_before_its_superclass_is_consulted}
 * pins that, and is the test to read before changing what this extends.
 */
public final class DefinitionAlreadyExistsException extends IllegalArgumentException {

    public DefinitionAlreadyExistsException(String message) {
        super(message);
    }
}
