package io.aeyer.plowshare.server.requests;

import io.aeyer.plowshare.server.faults.CallerFault;

/**
 * The two fields a definition is written from, refused here when either is
 * missing — before {@link io.aeyer.plowshare.server.agents.DefinitionWriter}
 * is ever asked to write anything.
 *
 * <p>Moved out of {@code AgentController.define}, where both were plain
 * {@code == null} checks on the request body, ahead of the size ceiling and
 * of {@code DefinitionWriter}'s own ladder of refusals — neither of which
 * belongs here: the ceiling is a resource rule reasoned about in
 * {@code DefinitionWriter}'s own terms, and the writer's ladder is a domain
 * decision the writer already owns. This class holds only the two checks that
 * decide nothing beyond "is the field there at all."
 *
 * <p>Deliberately {@code == null} rather than {@link String#isBlank()}: that
 * is what {@code AgentController.define} checked, and a blank {@code name} or
 * {@code text} is left to whatever the writer itself does with one, unchanged
 * by this move.
 */
public final class RequestedDefinition {

    private RequestedDefinition() {
    }

    /** {@code name}, or a {@link CallerFault} for a body that named none. */
    public static String name(String name) {
        if (name == null) {
            throw new CallerFault(
                    "'name' is required to define an agent, and this request named none");
        }
        return name;
    }

    /** {@code text}, or a {@link CallerFault} for a body that named none. */
    public static String text(String text) {
        if (text == null) {
            throw new CallerFault(
                    "'text' is required to define an agent, and this request named none");
        }
        return text;
    }
}
