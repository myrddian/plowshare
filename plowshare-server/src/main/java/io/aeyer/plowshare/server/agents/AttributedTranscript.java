package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.archive.Redemption;
import io.aeyer.plowshare.server.archive.StoredResults;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.server.llm.dispatch.ChatMessage;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Per-run carrier; parent ownership is captured at delegation rather than inferred from later
 * calls.
 */
public record AttributedTranscript(
    Transcript delegate, UsageAttribution usage, UsageAttribution parentUsage)
    implements Transcript {
  public AttributedTranscript {
    Objects.requireNonNull(delegate);
    Objects.requireNonNull(usage);
    Objects.requireNonNull(parentUsage);
  }

  @Override
  public void accounted(UsageAttribution owner) {
    delegate.accounted(owner);
  }

  @Override
  public List<ChatMessage> before() {
    return delegate.before();
  }

  @Override
  public void record(LoggedEntry entry) {
    delegate.record(entry);
  }

  @Override
  public String conversationId() {
    return delegate.conversationId();
  }

  @Override
  public Speaker speaker() {
    return delegate.speaker();
  }

  @Override
  public String opening() {
    return delegate.opening();
  }

  @Override
  public Origin origin() {
    return delegate.origin();
  }

  @Override
  public Spoken spokenIn() {
    return delegate.spokenIn();
  }

  @Override
  public Integer continuingTurn() {
    return delegate.continuingTurn();
  }

  @Override
  public Optional<Redemption> redeem(UUID handle) {
    return delegate.redeem(handle);
  }

  @Override
  public StoredResults stored(int skip, int most) {
    return delegate.stored(skip, most);
  }

  @Override
  public boolean followsAFallback() {
    return delegate.followsAFallback();
  }

  @Override
  public void closed(String utterance, Outcome outcome) {
    delegate.closed(utterance, outcome);
  }

  @Override
  public void promptMeasured(int tokens) {
    delegate.promptMeasured(tokens);
  }

  @Override
  public void answerMeasured(int tokens) {
    delegate.answerMeasured(tokens);
  }

  @Override
  public int stepEnded(List<ChatMessage> history, int openedAt) {
    return delegate.stepEnded(history, openedAt);
  }

  @Override
  public Transcript delegate(AgentDefinition callee, Home home, String openedBy) {
    return new AttributedTranscript(
        delegate.delegate(callee, home, openedBy), UsageAttribution.LEGACY, usage);
  }
}
