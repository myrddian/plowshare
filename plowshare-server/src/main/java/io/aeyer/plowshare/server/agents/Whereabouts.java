package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.protocol.Home;
import java.util.Optional;

/**
 * The harness telling a run where its project's files can be reached, when that
 * has changed since this conversation was last told. A notice, on {@link
 * Noticing}'s terms: logged after the utterance, never hidden, so it only extends
 * the prefix.
 *
 * <p><b>Why the harness says it at all.</b> A tool's answer exists only once the
 * tool is called. cnv_313AE867D7AEA6EE held a project rooted with `/here` and a
 * model offered {@code file_roots} on every turn that asked the person for "a
 * legitimate path inside a defined workspace" instead of calling it.
 */
public interface Whereabouts {

    Whereabouts NONE = (definition, home, conversation) -> Optional.empty();

    /** The notice for this turn, or empty when there is nothing new to say. */
    Optional<String> noticeFor(AgentDefinition definition, Home home, String conversation);
}
