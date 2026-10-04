package io.aeyer.plowshare.server.api;

/**
 * The body of {@code POST /v1/agents/{name}/runs}.
 *
 * @param task what the agent is being asked to do, in prose
 * @param project the tier the run answers from; {@code null} means global, matching {@code Home}'s
 *     own rule. A run's home is fixed at submission and the agent cannot widen it: {@code
 *     AgentTool.run} takes it as a parameter rather than offering it as an argument, so an agent
 *     runs against the tier its job was started for and no other.
 * @param session the id of the session this run is submitted under, or {@code null} for a caller
 *     that has none. <b>Optional, and that is the whole point of the field.</b> A client attached
 *     at {@code /v1/files} names its session here and the run can reach that machine's disk; a
 *     caller holding no socket — a script, a scheduled tick, another server — omits it and gets the
 *     server's own filesystems and works. The design spec's phrase is that such a run has "a
 *     smaller set, not an empty capability".
 *     <p>One word for one thing: this is the same id, spelled the same way, as the {@code session}
 *     query parameter both WebSocket roles take — {@code FileChannelHandler.SESSION_PARAM}. A
 *     submission naming a session nothing ever attached to is not an error; it is a session with
 *     nothing in it, and the run gets what a run with no session gets.
 * @param conversation the conversation this run is a turn in, or {@code null} for a run that is not
 *     one. <b>What it changes is the allowance and the home</b>: a turn spends the conversation's
 *     budget rather than a fresh copy of the agent's {@code max-model-calls}, and it runs in the
 *     home the conversation was opened in. {@code project} must therefore be left out when this is
 *     given — see {@code agents.Runs.start}, which refuses the pair rather than silently preferring
 *     one.
 *     <p>Absent is the ordinary shape of every caller that existed before conversations did: a
 *     curator pass, a scheduled tick, an agent delegating. A blank one is not pre-empted here —
 *     {@code ConversationStore.find} refuses it, before any job is minted, with the one message
 *     this server has about an id that cannot be named.
 * @param maxTurns how many turns this run may take, or {@code null} to take the cap from the level
 *     above — the conversation's, when this is an utterance in one, and otherwise the agent's own
 *     {@code max-turns}. <b>The narrowest of the three levels, and narrowest wins.</b> It is this
 *     run's only: an override on an utterance does not change what the conversation decided, and
 *     the next utterance starts from that again
 * @param images the UIDs of images this run is to be shown, from {@code POST /v1/images}. Absent or
 *     empty for the ordinary run, which is every run this server made before images existed.
 *     <p><b>UIDs and never bytes.</b> An agent names an image; the server attaches it. That is the
 *     whole shape of the feature: this field is a list of strings a submitter was handed by the
 *     upload route, the server resolves each against the run's own home, and no tool in the agent's
 *     hands can name, create or read one. A body carrying base64 would be a second door into the
 *     image store with none of the first one's refusals — the format sniff, the size cap, the
 *     record — so there is not one.
 *     <p><b>Resolved before a job id is handed back</b>, so a UID naming nothing is a 404 the
 *     submitter reads now rather than a job they poll to discover. {@code DocumentController}'s
 *     split, drawn in the same place: what is knowable in microseconds is answered on the request
 *     thread.
 *     <p>Not accepted beside {@code conversation}. A turn's opening message is the conversation's,
 *     and a picture that reached one turn and no other would be a conversation whose history cannot
 *     be replayed — {@code JobRuntime}'s note about what a resumed run does not get, arriving one
 *     level up. It is refused rather than silently dropped
 * @param noTurnCap whether this run is to have no turn cap at all. <b>Uncapped is said as a
 *     decision and never as a large number</b>; a body carrying this beside {@code maxTurns} is
 *     refused rather than resolved, because two answers to one question taken silently is how
 *     somebody comes to believe a run ran under a ceiling it never had. There is no matching
 *     override for the budget, and {@code JobStore.submit} and {@code Turn.speak} each say why from
 *     their own side: the budget is the cost bound, it belongs to a conversation or to a
 *     definition, and a run granting itself one would be spending something no row knows about
 * @param newConversation open a fresh conversation before submitting its first turn; cannot be
 *     combined with a conversation id or images
 */
public record RunAgentRequest(
    String task,
    String project,
    String session,
    String conversation,
    Integer maxTurns,
    Boolean noTurnCap,
    java.util.List<String> images,
    Boolean newConversation) {

  public RunAgentRequest(
      String task,
      String project,
      String session,
      String conversation,
      Integer maxTurns,
      Boolean noTurnCap,
      java.util.List<String> images) {
    this(task, project, session, conversation, maxTurns, noTurnCap, images, null);
  }

  /** Never null, so the one caller does not branch before it iterates. */
  public RunAgentRequest {
    images = images == null ? java.util.List.of() : java.util.List.copyOf(images);
  }
}
