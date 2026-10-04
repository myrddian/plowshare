package io.aeyer.plowshare.server.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.AgentRegistry;
import io.aeyer.plowshare.server.agents.Callers;
import io.aeyer.plowshare.server.agents.DefinitionResolver;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.server.llm.accounting.UsageOwners;
import io.aeyer.plowshare.server.llm.dispatch.ChatMessage;
import io.aeyer.plowshare.server.llm.dispatch.ChatRequest;
import io.aeyer.plowshare.server.llm.dispatch.Completion;
import io.aeyer.plowshare.server.llm.dispatch.Lane;
import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import io.aeyer.plowshare.server.llm.dispatch.LlmSaturatedException;
import io.aeyer.plowshare.server.llm.dispatch.LlmTransportException;
import io.aeyer.plowshare.server.llm.dispatch.Sampling;
import io.aeyer.plowshare.server.llm.dispatch.TokenUsage;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The reading of one sentence into a proposal, with the model's answer scripted.
 *
 * <p>Everything this class asserts is what the server does <em>around</em> the model: which agents
 * it is shown, what it is allowed to choose, and the three things — fire times, names, the
 * destination — that are computed here rather than trusted from an answer.
 */
class ScheduleReaderTest {

  /** A Sunday noon, so the first weekday fire is Monday and the zone offset is visible. */
  private static final Instant NOW = Instant.parse("2026-09-13T12:00:00Z");

  private static final DefinitionResolver.Caller PAYMENTS = new DefinitionResolver.Caller(7L, null);
  private static final DefinitionResolver.Caller GLOBAL = new DefinitionResolver.Caller(null, null);

  private LlmDispatcher dispatcher;
  private DefinitionResolver resolver;
  private Callers callers;
  private ScheduleReader reader;
  private AgentRegistry boot;
  private final List<Runnable> appenders = new ArrayList<>();

  private static AgentDefinition agent(String name, String description, boolean exported) {
    return new AgentDefinition(
        name,
        description,
        "fast",
        List.of(),
        List.of(),
        List.of(),
        1,
        1,
        "prompt of " + name,
        exported,
        false);
  }

  /** A server bot: exported, and {@code bot: true}. */
  private static AgentDefinition bot(String name, String description) {
    return new AgentDefinition(
        name,
        description,
        "fast",
        AgentDefinition.DEFAULT_INTENT,
        Sampling.NONE,
        List.of(),
        List.of(),
        List.of(),
        1,
        1,
        "prompt of " + name,
        true,
        false,
        false,
        true);
  }

  @BeforeEach
  void setUp() {
    dispatcher = mock(LlmDispatcher.class);
    resolver = mock(DefinitionResolver.class);
    callers = mock(Callers.class);
    boot =
        new AgentRegistry(
            Map.of(
                ScheduleReader.AGENT,
                agent(ScheduleReader.AGENT, "reads a sentence into a schedule", false)));
    AgentRegistry tier =
        new AgentRegistry(
            Map.of(
                "interlocutor", agent("interlocutor", "talks with a person", true),
                "aristoxenus", bot("aristoxenus", "the default bot"),
                "scribe", agent("scribe", "files memories", false)));
    when(resolver.forCaller(any())).thenReturn(tier);
    when(resolver.defaultBot(any())).thenReturn(Optional.empty());
    when(callers.callerFor(eq("payments"), isNull())).thenReturn(PAYMENTS);
    when(callers.callerFor(isNull(), isNull())).thenReturn(GLOBAL);
    when(callers.callerForConversation(eq("cnv_1"), isNull())).thenReturn(PAYMENTS);
    reader = new ScheduleReader(dispatcher, () -> boot, resolver, callers, () -> NOW, () -> "3f2a");
  }

  @AfterEach
  void tearDown() {
    appenders.forEach(Runnable::run);
  }

  private void answering(String json) {
    when(dispatcher.complete(any()))
        .thenReturn(new Completion(json, "stop", TokenUsage.UNKNOWN, List.of()));
  }

  /** A logback appender on {@link ScheduleReader}'s own logger, detached in {@link #tearDown()}. */
  private ListAppender<ILoggingEvent> recording() {
    ch.qos.logback.classic.Logger logger =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ScheduleReader.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    appenders.add(() -> logger.detachAppender(appender));
    return appender;
  }

  private static String answer(String cron, String agent, String task, boolean into) {
    return "{\"cron\":\""
        + cron
        + "\",\"when\":\"every weekday at 09:00\",\"agent\":\""
        + agent
        + "\",\"task\":\""
        + task
        + "\",\"intoConversation\":"
        + into
        + "}";
  }

  private static final String SENTENCE =
      "every weekday at 9am have the interlocutor summarise what changed in my projects yesterday";

  @Test
  void enabled_sentence_reading_uses_the_signed_requester_not_model_claims() {
    var owners = mock(UsageOwners.class);
    var owner = UsageAttribution.project("alice", "7", UsageAttribution.Operation.SCHEDULE_READ);
    when(owners.in(io.aeyer.plowshare.protocol.Home.of("payments"), "alice", owner.operation()))
        .thenReturn(owner);
    reader.useUsageOwners(owners);
    when(callers.callerFor("payments", null, "alice"))
        .thenReturn(new DefinitionResolver.Caller(7L, null, "alice"));
    answering(answer("0 0 9 * * MON-FRI", "interlocutor", "Do the task for bob.", false));
    reader.read(new ScheduleReader.Request(SENTENCE, "Europe/London", "payments", null), "alice");
    var sent = ArgumentCaptor.forClass(ChatRequest.class);
    verify(dispatcher).complete(sent.capture());
    assertEquals(
        owner.forOperation(owner.operation(), ScheduleReader.AGENT), sent.getValue().attribution());
    verify(callers).callerFor("payments", null, "alice");
  }

  @Test
  void a_sentence_is_read_into_a_proposal_whose_fires_and_names_are_computed_here() {
    answering(
        answer(
            "0 0 9 * * MON-FRI",
            "interlocutor",
            "Summarise what changed in my projects yesterday.",
            false));

    ScheduleProposal proposal =
        reader.read(new ScheduleReader.Request(SENTENCE, "Europe/London", "payments", null));

    assertEquals("0 0 9 * * MON-FRI", proposal.cron());
    assertEquals("Europe/London", proposal.zone());
    assertEquals("every weekday at 09:00", proposal.when());
    assertEquals("interlocutor", proposal.agent());
    assertEquals("Summarise what changed in my projects yesterday.", proposal.task());
    assertFalse(proposal.intoConversation());
    assertEquals("payments", proposal.project());
    assertNull(proposal.conversation());
    // Nine in London in September is eight UTC; Sunday noon's next weekday is Monday.
    assertEquals(
        List.of(
            Instant.parse("2026-09-14T08:00:00Z"),
            Instant.parse("2026-09-15T08:00:00Z"),
            Instant.parse("2026-09-16T08:00:00Z")),
        proposal.nextFires());
    ScheduleProposal.Names expected =
        new ScheduleProposal.Names(
            "summarise-what-changed-3f2a",
            "summarise-what-changed-3f2a",
            "summarise-what-changed-3f2a");
    assertEquals(expected, proposal.names());
    verify(callers).requireAgent("interlocutor", PAYMENTS);
  }

  @Test
  void the_model_is_shown_the_sentence_the_zone_and_only_the_agents_this_tier_can_run() {
    answering(answer("0 0 9 * * MON-FRI", "interlocutor", "Summarise.", false));
    when(resolver.defaultBot(any()))
        .thenReturn(Optional.of(new DefinitionResolver.DefaultBot("aristoxenus", "bots/default")));

    reader.read(new ScheduleReader.Request(SENTENCE, "Europe/London", "payments", null));

    ArgumentCaptor<ChatRequest> sent = ArgumentCaptor.forClass(ChatRequest.class);
    verify(dispatcher).complete(sent.capture());
    List<ChatMessage> messages = sent.getValue().messages();
    assertEquals(2, messages.size());
    assertEquals("prompt of " + ScheduleReader.AGENT, messages.get(0).content());
    String opening = messages.get(1).content();
    assertTrue(opening.contains(SENTENCE), opening);
    assertTrue(opening.contains("Europe/London"), opening);
    assertTrue(opening.contains("- interlocutor (agent): talks with a person"), opening);
    assertTrue(opening.contains("- aristoxenus (bot): the default bot"), opening);
    assertTrue(opening.contains("aristoxenus"), opening);
    assertFalse(opening.contains("scribe"), opening);
    assertTrue(opening.contains("default bot: aristoxenus"), opening);
    verify(resolver).forCaller(PAYMENTS);
  }

  @Test
  void a_request_with_no_zone_is_read_in_utc() {
    answering(answer("0 0 9 * * *", "interlocutor", "Summarise.", false));

    ScheduleProposal proposal = reader.read(new ScheduleReader.Request(SENTENCE, null, null, null));

    assertEquals("UTC", proposal.zone());
    assertEquals(Instant.parse("2026-09-14T09:00:00Z"), proposal.nextFires().get(0));
  }

  @Test
  void results_go_into_the_conversation_only_when_the_request_named_one() {
    answering(answer("0 0 9 * * *", "interlocutor", "Summarise.", true));

    ScheduleProposal here =
        reader.read(new ScheduleReader.Request("do it here", "UTC", null, "cnv_1"));
    assertTrue(here.intoConversation());
    assertEquals("cnv_1", here.conversation());
    assertNull(here.project());
    verify(callers).requireAgent("interlocutor", PAYMENTS);

    ScheduleProposal nowhere =
        reader.read(new ScheduleReader.Request("do it here", "UTC", "payments", null));
    assertFalse(nowhere.intoConversation());
    assertNull(nowhere.conversation());
    assertEquals("payments", nowhere.project());
  }

  @Test
  void a_conversation_the_sentence_does_not_ask_for_sends_results_to_the_inbox() {
    answering(answer("0 0 9 * * *", "interlocutor", "Summarise.", false));

    ScheduleProposal proposal =
        reader.read(new ScheduleReader.Request(SENTENCE, "UTC", null, "cnv_1"));

    assertFalse(proposal.intoConversation());
    assertNull(proposal.conversation());
    assertNull(proposal.project());
    // The trigger the client will save names neither, so it runs in the global tier, and
    // that is where the agent has to be runnable.
    verify(callers).requireAgent("interlocutor", GLOBAL);
  }

  @Test
  void a_cron_the_server_cannot_read_is_refused() {
    answering(answer("every weekday", "interlocutor", "Summarise.", false));

    CallerFault refused =
        assertThrows(
            CallerFault.class,
            () -> reader.read(new ScheduleReader.Request(SENTENCE, "UTC", null, null)));
    assertTrue(refused.getMessage().contains("every weekday"), refused.getMessage());
  }

  @Test
  void an_agent_this_tier_cannot_run_is_refused_and_never_checked_further() {
    for (String chosen : List.of("scribe", "nobody")) {
      answering(answer("0 0 9 * * *", chosen, "Summarise.", false));

      CallerFault refused =
          assertThrows(
              CallerFault.class,
              () -> reader.read(new ScheduleReader.Request(SENTENCE, "UTC", null, null)));
      assertTrue(refused.getMessage().contains(chosen), refused.getMessage());
    }
    verify(callers, never()).requireAgent(any(), any());
  }

  @Test
  void a_blank_task_is_refused() {
    answering(answer("0 0 9 * * *", "interlocutor", "  ", false));

    CallerFault refused =
        assertThrows(
            CallerFault.class,
            () -> reader.read(new ScheduleReader.Request(SENTENCE, "UTC", null, null)));
    assertTrue(refused.getMessage().contains("task"), refused.getMessage());
  }

  /** The model's answer when the sentence names nobody and it was given no default. */
  private static final String NOBODY =
      "{\"cron\":\"0 5 * * *\",\"when\":\"every day at five\","
          + "\"agent\":\"\",\"task\":\"Get me the latest local news.\","
          + "\"intoConversation\":false,\"unreadable\":\"\"}";

  @Test
  void a_blank_agent_goes_to_the_default_bot_of_the_home_the_result_goes_to() {
    when(resolver.forCaller(any()))
        .thenReturn(
            new AgentRegistry(
                Map.of(
                    "interlocutor", agent("interlocutor", "talks with a person", true),
                    "aristoxenus", bot("aristoxenus", "the default bot"),
                    "boethius", bot("boethius", "another bot"))));
    when(resolver.defaultBot(PAYMENTS))
        .thenReturn(Optional.of(new DefinitionResolver.DefaultBot("boethius", "bots/default")));
    answering(NOBODY);

    ScheduleProposal proposal =
        reader.read(new ScheduleReader.Request(SENTENCE, "UTC", "payments", null));

    assertEquals("boethius", proposal.agent());
    verify(callers).requireAgent("boethius", PAYMENTS);
  }

  @Test
  void a_blank_agent_with_no_default_goes_to_the_only_server_bot() {
    answering(NOBODY);

    ScheduleProposal proposal =
        reader.read(new ScheduleReader.Request(SENTENCE, "UTC", "payments", null));

    assertEquals("aristoxenus", proposal.agent());
    assertEquals("Get me the latest local news.", proposal.task());
    verify(callers).requireAgent("aristoxenus", PAYMENTS);
  }

  @Test
  void a_blank_agent_with_no_default_and_two_bots_is_refused_naming_both() {
    when(resolver.forCaller(any()))
        .thenReturn(
            new AgentRegistry(
                Map.of(
                    "interlocutor", agent("interlocutor", "talks with a person", true),
                    "aristoxenus", bot("aristoxenus", "the default bot"),
                    "boethius", bot("boethius", "another bot"))));
    answering(NOBODY);
    ListAppender<ILoggingEvent> recorded = recording();

    CallerFault refused =
        assertThrows(
            CallerFault.class,
            () -> reader.read(new ScheduleReader.Request(SENTENCE, "UTC", null, null)));

    assertEquals(
        "say which bot should do it: aristoxenus, boethius. Nothing was proposed.",
        refused.getMessage());
    verify(callers, never()).requireAgent(any(), any());
    assertTrue(recorded.list.get(0).getFormattedMessage().contains(NOBODY));
  }

  @Test
  void a_blank_agent_with_no_bots_at_all_is_refused_saying_so() {
    when(resolver.forCaller(any()))
        .thenReturn(
            new AgentRegistry(
                Map.of("interlocutor", agent("interlocutor", "talks with a person", true))));
    answering(NOBODY);

    CallerFault refused =
        assertThrows(
            CallerFault.class,
            () -> reader.read(new ScheduleReader.Request(SENTENCE, "UTC", null, null)));

    assertEquals(
        "say which bot should do it: no bots are defined on this server. Nothing was"
            + " proposed.",
        refused.getMessage());
    verify(callers, never()).requireAgent(any(), any());
  }

  @Test
  void a_named_agent_that_is_not_a_bot_is_still_accepted() {
    when(resolver.defaultBot(any()))
        .thenReturn(Optional.of(new DefinitionResolver.DefaultBot("aristoxenus", "bots/default")));
    answering(answer("0 0 9 * * *", "interlocutor", "Summarise.", false));

    ScheduleProposal proposal =
        reader.read(new ScheduleReader.Request(SENTENCE, "UTC", "payments", null));

    assertEquals("interlocutor", proposal.agent());
    verify(callers).requireAgent("interlocutor", PAYMENTS);
  }

  @Test
  void a_reading_that_says_what_it_could_not_read_is_refused_with_those_words() {
    answering("{\"unreadable\":\"couldn't tell when: 'sometimes in the morning'\"}");

    CallerFault refused =
        assertThrows(
            CallerFault.class,
            () ->
                reader.read(
                    new ScheduleReader.Request("sometimes in the morning", "UTC", null, null)));
    assertTrue(
        refused.getMessage().contains("couldn't tell when: 'sometimes in the morning'"),
        refused.getMessage());
  }

  /**
   * The production bug this whole change was made for: a model that answers a long, compound
   * sentence with every field but {@code cron} and an empty {@code unreadable}. Reproduces the
   * exact answer the report was filed with.
   */
  @Test
  void a_reading_with_no_cron_and_no_unreadable_is_refused_clearly_and_logs_the_raw_answer() {
    String raw =
        "{\"when\":\"every day at 5am\",\"agent\":\"interlocutor\",\"task\":\"Get"
            + " the news.\",\"intoConversation\":false,\"unreadable\":\"\"}";
    answering(raw);
    ListAppender<ILoggingEvent> recorded = recording();

    CallerFault refused =
        assertThrows(
            CallerFault.class,
            () -> reader.read(new ScheduleReader.Request(SENTENCE, "UTC", null, null)));

    assertEquals(
        "the reading gave no schedule for that sentence; try saying the time"
            + " another way. Nothing was proposed.",
        refused.getMessage());
    // Not CronSchedule's own message about "0 0 9 * * *", which quotes an example the
    // person never wrote and reads as though their own sentence had been echoed back wrong.
    assertFalse(refused.getMessage().contains("0 0 9 * * *"), refused.getMessage());

    List<ILoggingEvent> warnings =
        recorded.list.stream().filter(event -> event.getLevel() == Level.WARN).toList();
    assertEquals(1, warnings.size(), "one refusal, one record — " + recorded.list);
    String logged = warnings.get(0).getFormattedMessage();
    assertTrue(
        logged.contains(raw),
        "the raw model answer, for whoever has to debug this" + " next — " + logged);
    assertTrue(logged.contains("no schedule"), "and why it was refused — " + logged);
  }

  @Test
  void an_explicit_empty_unreadable_alongside_a_valid_reading_is_a_proposal_not_a_refusal() {
    answering(
        "{\"cron\":\"0 0 9 * * *\",\"when\":\"every day at nine\",\"agent\":"
            + "\"interlocutor\",\"task\":\"Summarise.\",\"intoConversation\":false,"
            + "\"unreadable\":\"\"}");

    ScheduleProposal proposal =
        reader.read(new ScheduleReader.Request(SENTENCE, "UTC", null, null));

    assertEquals("0 0 9 * * *", proposal.cron());
    assertEquals("interlocutor", proposal.agent());
  }

  @Test
  void a_successful_reading_leaves_no_warning_in_the_log() {
    answering(
        answer(
            "0 0 9 * * MON-FRI",
            "interlocutor",
            "Summarise what changed in my projects yesterday.",
            false));
    ListAppender<ILoggingEvent> recorded = recording();

    reader.read(new ScheduleReader.Request(SENTENCE, "Europe/London", "payments", null));

    assertEquals(
        List.of(), recorded.list.stream().filter(event -> event.getLevel() == Level.WARN).toList());
  }

  @Test
  void a_very_long_raw_answer_is_truncated_rather_than_logged_whole() {
    String longTask = "x".repeat(2000);
    answering(
        "{\"when\":\"every day\",\"agent\":\"interlocutor\",\"task\":\""
            + longTask
            + "\",\"intoConversation\":false,\"unreadable\":\"\"}");
    ListAppender<ILoggingEvent> recorded = recording();

    assertThrows(
        CallerFault.class,
        () -> reader.read(new ScheduleReader.Request(SENTENCE, "UTC", null, null)));

    String logged = recorded.list.get(0).getFormattedMessage();
    assertTrue(
        logged.length() < 700,
        "the raw answer must be truncated to about 500"
            + " characters, not logged whole — length was "
            + logged.length());
  }

  @Test
  void an_answer_that_is_not_json_is_refused_rather_than_guessed_at() {
    answering("every weekday, I think");

    assertThrows(
        CallerFault.class,
        () -> reader.read(new ScheduleReader.Request(SENTENCE, "UTC", null, null)));
  }

  @Test
  void a_model_that_cannot_be_reached_is_unavailable_and_names_no_endpoint() {
    when(dispatcher.complete(any()))
        .thenThrow(new LlmTransportException("[lmstudio ws://10.0.0.7:1234] websocket failed"));

    ModelUnavailableException away =
        assertThrows(
            ModelUnavailableException.class,
            () -> reader.read(new ScheduleReader.Request(SENTENCE, "UTC", null, null)));
    assertEquals(
        "the model that reads schedules could not be reached; nothing was proposed"
            + " and nothing was saved",
        away.getMessage());
  }

  @Test
  void a_saturated_model_is_unavailable_too() {
    when(dispatcher.complete(any()))
        .thenThrow(new LlmSaturatedException("chat", Lane.CHAT, Duration.ofSeconds(10), 3));

    assertThrows(
        ModelUnavailableException.class,
        () -> reader.read(new ScheduleReader.Request(SENTENCE, "UTC", null, null)));
  }

  /**
   * Scribe's distinction: a definition this server cannot turn into a request is this server being
   * wrong, not the endpoint being away, and it must not read as an outage.
   */
  @Test
  void a_failure_that_is_not_the_endpoint_is_not_called_an_outage() {
    when(dispatcher.complete(any())).thenThrow(new IllegalArgumentException("bad model"));

    assertThrows(
        IllegalArgumentException.class,
        () -> reader.read(new ScheduleReader.Request(SENTENCE, "UTC", null, null)));
  }

  @Test
  void the_model_is_told_what_day_it_is_in_the_zone() {
    answering(answer("0 0 9 * * *", "interlocutor", "Summarise.", false));
    // 23:30 UTC on Sunday is already Monday in Tokyo.
    ScheduleReader late =
        new ScheduleReader(
            dispatcher,
            () -> boot,
            resolver,
            callers,
            () -> Instant.parse("2026-09-13T23:30:00Z"),
            () -> "3f2a");

    late.read(new ScheduleReader.Request(SENTENCE, "Asia/Tokyo", null, null));

    ArgumentCaptor<ChatRequest> sent = ArgumentCaptor.forClass(ChatRequest.class);
    verify(dispatcher).complete(sent.capture());
    String opening = sent.getValue().messages().get(1).content();
    assertTrue(opening.contains("Today in Asia/Tokyo is Monday, 2026-09-14."), opening);
  }

  @Test
  void both_homes_offer_their_agents_and_a_reading_into_the_conversation_is_checked_there() {
    tiers();
    answering(answer("0 0 9 * * *", "helper", "Summarise.", true));

    ScheduleProposal proposal =
        reader.read(new ScheduleReader.Request("here, please", "UTC", "payments", "cnv_2"));

    assertTrue(proposal.intoConversation());
    assertEquals("cnv_2", proposal.conversation());
    assertNull(proposal.project());
    verify(callers).requireAgent("helper", RESEARCH);
    ArgumentCaptor<ChatRequest> sent = ArgumentCaptor.forClass(ChatRequest.class);
    verify(dispatcher).complete(sent.capture());
    String opening = sent.getValue().messages().get(1).content();
    assertTrue(
        opening.contains("- helper (agent):") && opening.contains("- billing (agent):"), opening);
  }

  @Test
  void both_homes_offer_their_agents_and_a_reading_for_the_inbox_is_checked_in_the_project() {
    tiers();
    answering(answer("0 0 9 * * *", "billing", "Summarise.", false));

    ScheduleProposal proposal =
        reader.read(new ScheduleReader.Request(SENTENCE, "UTC", "payments", "cnv_2"));

    assertFalse(proposal.intoConversation());
    assertNull(proposal.conversation());
    assertEquals("payments", proposal.project());
    verify(callers).requireAgent("billing", PAYMENTS);
  }

  @Test
  void an_agent_offered_from_the_other_home_is_refused_where_the_result_actually_goes() {
    tiers();
    answering(answer("0 0 9 * * *", "helper", "Summarise.", false));

    CallerFault refused =
        assertThrows(
            CallerFault.class,
            () -> reader.read(new ScheduleReader.Request(SENTENCE, "UTC", "payments", "cnv_2")));
    assertTrue(refused.getMessage().contains("'helper'"), refused.getMessage());
    assertTrue(refused.getMessage().contains("inbox"), refused.getMessage());
    verify(callers, never()).requireAgent(any(), any());
  }

  private static final DefinitionResolver.Caller RESEARCH = new DefinitionResolver.Caller(9L, null);

  /**
   * A project tier and a conversation that lives in a different one, each with an agent the other
   * cannot run.
   */
  private void tiers() {
    when(callers.callerForConversation(eq("cnv_2"), isNull())).thenReturn(RESEARCH);
    when(resolver.forCaller(PAYMENTS))
        .thenReturn(new AgentRegistry(Map.of("billing", agent("billing", "reads invoices", true))));
    when(resolver.forCaller(RESEARCH))
        .thenReturn(
            new AgentRegistry(Map.of("helper", agent("helper", "helps with research", true))));
  }

  @Test
  void a_blank_sentence_is_refused_before_the_model_is_asked() {
    assertThrows(
        CallerFault.class, () -> reader.read(new ScheduleReader.Request("  ", "UTC", null, null)));
    verify(dispatcher, never()).complete(any());
  }

  @Test
  void a_name_is_the_first_words_of_the_task_and_the_suffix() {
    assertEquals(
        "summarise-what-changed-3f2a",
        ScheduleReader.slug("Summarise what changed in my projects yesterday.", "3f2a"));
    assertEquals("check-the-c-3f2a", ScheduleReader.slug("Check   the C.I.!", "3f2a"));
    assertEquals("schedule-3f2a", ScheduleReader.slug("!!!", "3f2a"));
  }

  @Test
  void a_five_field_cron_is_prefixed_with_a_seconds_field_of_zero() {
    assertEquals("0 0 5 * * *", ScheduleReader.sixFields("0 5 * * *"));
  }

  @Test
  void a_six_field_cron_is_left_unchanged() {
    assertEquals("0 0 9 * * MON-FRI", ScheduleReader.sixFields("0 0 9 * * MON-FRI"));
  }

  @Test
  void extra_whitespace_between_fields_is_collapsed_before_counting_them() {
    assertEquals("0 0 5 * * *", ScheduleReader.sixFields("0   5  *   * *"));
  }

  @Test
  void a_field_count_neither_form_uses_is_left_for_cronschedule_to_refuse() {
    assertEquals("* * * *", ScheduleReader.sixFields("* * * *"));
    assertEquals("* * * * * * *", ScheduleReader.sixFields("* * * * * * *"));
  }

  /**
   * The production bug: the small {@code fast} model answers with classic five-field Unix cron
   * ({@code "0 5 * * *"}) despite the prompt asking for Spring's six. The proposal's cron is the
   * normalised six-field string — what {@code schedule.define} will be asked to save — and the fire
   * times the person checks are computed from that same string.
   */
  @Test
  void a_five_field_cron_from_the_model_is_read_as_springs_six_seconds_zero() {
    answering(answer("0 5 * * *", "interlocutor", "Get the latest local news.", false));

    ScheduleProposal proposal =
        reader.read(new ScheduleReader.Request(SENTENCE, "Australia/Melbourne", "payments", null));

    assertEquals("0 0 5 * * *", proposal.cron());
    // Sunday noon UTC is Sunday 22:00 in Melbourne (AEST, UTC+10, no DST until October);
    // the next 5am local is Monday 05:00, which is Sunday 19:00 UTC.
    assertEquals(Instant.parse("2026-09-13T19:00:00Z"), proposal.nextFires().get(0));
  }
}
