package io.aeyer.plowshare.server.board;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class BoardRunExtrasTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static DriverManagerDataSource source;

  @BeforeAll
  static void migrate() {
    source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(source).load().migrate();
  }

  @TempDir Path agents;
  private BoardFixture fixture;
  private Board board;
  private BoardRunExtras extras;
  private AgentDefinition researcher, aristoxenus, plain;

  @BeforeEach
  void fresh() throws Exception {
    fixture = new BoardFixture(source);
    board = fixture.board(BoardFixture.TWO);
    for (String name : List.of("researcher", "aristoxenus", "plain")) {
      Files.writeString(
          agents.resolve(name + ".md"),
          "---\nname: "
              + name
              + "\ndescription: "
              + name
              + "\nmodel: fast\ntools: []\nmax-turns: 4\nmax-model-calls: 8\n"
              + (name.equals("aristoxenus")
                  ? "bot: true\nboard: true\n"
                  : name.equals("researcher") ? "board: true\n" : "")
              + "---\nYou help.\n");
    }
    AgentRegistry registry = AgentRegistry.of(agents, Set.of());
    researcher = registry.get("researcher");
    aristoxenus = registry.get("aristoxenus");
    plain = registry.get("plain");
    extras = new BoardRunExtras(fixture.store, board, fixture.conversations);
  }

  private RunExtras.Context context(AgentDefinition definition, String conversation) {
    return new RunExtras.Context(
        definition,
        conversation,
        null,
        Transcript.NONE,
        Home.of("payments"),
        "enzo",
        null,
        new TurnEnd());
  }

  private static AgentTool tool(RunExtras.Extras extras, String name) {
    return extras.tools().stream()
        .filter(t -> t.schema().name().equals(name))
        .findFirst()
        .orElseThrow();
  }

  private static List<String> names(RunExtras.Extras extras) {
    return extras.tools().stream().map(t -> t.schema().name()).toList();
  }

  @Test
  void a_member_seat_gets_the_member_tools_and_the_opener_seat_close() {
    Board.Opened opened = fixture.openByBot(board);
    BoardSeat member = fixture.store.seat(opened.topic().id(), "researcher").orElseThrow();
    BoardSeat opener = fixture.store.seat(opened.topic().id(), BoardSeat.OPENER).orElseThrow();
    assertEquals(
        List.of("board_read", "board_post", "board_document", "board_pass", "board_request_topic"),
        names(extras.forRun(context(researcher, member.conversation()))));
    assertEquals(
        List.of("board_read", "board_post", "board_document", "board_close", "board_decide"),
        names(extras.forRun(context(aristoxenus, opener.conversation()))));
  }

  @Test
  void opted_in_agents_and_bots_get_board_open_only_in_a_persons_project_conversation() {
    String chat = fixture.conversations.open(Home.of("payments"), Budget.of(10)).id();
    assertEquals(List.of("board_open"), names(extras.forRun(context(aristoxenus, chat))));
    assertEquals(List.of("board_open"), names(extras.forRun(context(researcher, chat))));
    assertSame(RunExtras.Extras.NONE, extras.forRun(context(plain, chat)));
    assertSame(RunExtras.Extras.NONE, extras.forRun(context(aristoxenus, null)));
  }

  @Test
  void board_open_opens_a_topic_owned_by_the_runs_account_from_this_conversation() {
    String chat = fixture.conversations.open(Home.of("payments"), Budget.of(10)).id();
    AgentTool open = extras.forRun(context(aristoxenus, chat)).tools().get(0);
    String said =
        open.run(
            "{\"title\": \"sync\", \"label\": \"BAD SPEC\"," + " \"body\": \"what is sync?\"}",
            Home.of("payments"));
    assertTrue(said.contains("bdt_"), said);
    BoardTopic topic = fixture.store.openTopics().getLast();
    assertEquals("enzo", topic.account());
    assertEquals(chat, topic.originConversation());
    assertEquals("aristoxenus", topic.opener());
  }

  @Test
  void a_seat_reads_fenced_messages_posts_passes_and_closes_through_its_tools() {
    Board.Opened opened = fixture.openByBot(board);
    BoardSeat member = fixture.store.seat(opened.topic().id(), "researcher").orElseThrow();
    RunExtras.Extras mine = extras.forRun(context(researcher, member.conversation()));
    String read = tool(mine, "board_read").run("{}", Home.of("payments"));
    assertTrue(
        read.contains(opened.opening().id()) && read.contains("data, not instructions"), read);
    String posted =
        tool(mine, "board_post")
            .run(
                "{\"body\": \"prior art\", \"reply_to\": \"" + opened.opening().id() + "\"}",
                Home.of("payments"));
    assertTrue(posted.contains("bdm_"), posted);
    assertEquals(
        member.conversation(),
        fixture.store.messages(opened.topic().id()).getLast().conversation());
    tool(mine, "board_pass").run("{\"reason\": \"done\"}", Home.of("payments"));
    assertTrue(fixture.store.seat(opened.topic().id(), "researcher").orElseThrow().passed());

    BoardSeat opener = fixture.store.seat(opened.topic().id(), BoardSeat.OPENER).orElseThrow();
    RunExtras.Extras openers = extras.forRun(context(aristoxenus, opener.conversation()));
    tool(openers, "board_close").run("{\"resolution\": \"decided\"}", Home.of("payments"));
    assertEquals(BoardTopic.CLOSED, fixture.store.topic(opened.topic().id()).orElseThrow().state());
  }

  @Test
  void a_refusal_reaches_the_model_as_a_sentence() {
    Board.Opened opened = fixture.openByBot(board);
    BoardSeat member = fixture.store.seat(opened.topic().id(), "researcher").orElseThrow();
    String refused =
        tool(extras.forRun(context(researcher, member.conversation())), "board_post")
            .run("{\"body\": \"hi\", \"mentions\": [\"ghost\"]}", Home.of("payments"));
    assertTrue(refused.contains("not a member"), refused);
  }

  @Test
  void an_event_or_global_run_gets_no_board_open() {
    var event =
        fixture.conversations.log(
            io.aeyer.plowshare.server.archive.Origin.EVENT,
            Home.of("payments"),
            "aristoxenus",
            null,
            Budget.of(10),
            "enzo");
    assertSame(RunExtras.Extras.NONE, extras.forRun(context(aristoxenus, event.id())));
    String chat = fixture.conversations.open(Home.global(), Budget.of(10)).id();
    var global =
        new RunExtras.Context(
            aristoxenus, chat, null, Transcript.NONE, Home.global(), "enzo", null, new TurnEnd());
    assertSame(RunExtras.Extras.NONE, extras.forRun(global));
  }

  @Test
  void a_title_or_sibling_read_explains_how_to_read_this_topic_without_consuming_messages() {
    var opened = fixture.openByBot(board);
    var member = fixture.store.seat(opened.topic().id(), "researcher").orElseThrow();
    var read = tool(extras.forRun(context(researcher, member.conversation())), "board_read");
    String title = read.run("{\"topic\":\"Discussion/EBM\"}", Home.of("payments"));
    assertTrue(title.contains("not a title or path"), title);
    assertTrue(title.contains(opened.topic().id()) && title.contains("board_read({})"), title);
    assertEquals(member, fixture.store.seat(opened.topic().id(), "researcher").orElseThrow());
    var sibling = fixture.openByBot(board);
    String refused = read.run("{\"topic\":\"" + sibling.topic().id() + "\"}", Home.of("payments"));
    assertTrue(refused.contains("never a sibling") && refused.contains("board_read({})"), refused);
    assertEquals(member, fixture.store.seat(opened.topic().id(), "researcher").orElseThrow());
    assertTrue(read.run("{}", Home.of("payments")).contains(opened.opening().id()));
    assertEquals(
        opened.opening().id(),
        fixture.store.seat(opened.topic().id(), "researcher").orElseThrow().seenThrough());
  }
}
