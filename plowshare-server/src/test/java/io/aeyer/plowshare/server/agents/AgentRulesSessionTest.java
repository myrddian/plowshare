package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.FileRequest;
import io.aeyer.plowshare.protocol.Span;
import io.aeyer.plowshare.server.data.DataLayout;
import io.aeyer.plowshare.server.files.SessionChannel;
import io.aeyer.plowshare.server.files.SessionGoneException;
import io.aeyer.plowshare.server.files.WorkspaceUnavailableException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class AgentRulesSessionTest {
  @Test
  void closing_mid_rule_read_preserves_the_transport_failure_and_never_serves_partial_rules() {
    var failed = new SessionGoneException("file connection closed");
    var requests = new ArrayList<FileRequest>();
    var files = new FakeFiles().withFile("AGENTS.md", "Required root instructions");
    SessionChannel channel =
        (session, request) -> {
          requests.add(request);
          if (request.path().equals(".plowshare/AGENTS.md")) throw failed;
          return files.ask(session, request);
        };
    var rules =
        new AgentRules(DataLayout.NONE, channel, session -> true, (project, session) -> true);
    assertSame(
        failed,
        assertThrows(
            SessionGoneException.class,
            () -> rules.forPath(new DefinitionResolver.Caller(7L, "client"), "worker", null)));
    assertTrue(requests.stream().noneMatch(r -> r.path().contains("agents/worker")));
    assertTrue(requests.stream().allMatch(r -> FileRequest.DEFINITIONS.equals(r.purpose())));
  }

  @Test
  void a_timeout_is_not_misreported_as_a_closed_session_or_absent_file() {
    var failed = new WorkspaceUnavailableException("file read deadline elapsed");
    SessionChannel channel =
        (session, request) -> {
          throw failed;
        };
    var files = ChannelDefinitions.skills(channel, "client");
    assertSame(
        failed,
        assertThrows(WorkspaceUnavailableException.class, () -> files.resource("AGENTS.md")));
    assertSame(
        failed,
        assertThrows(WorkspaceUnavailableException.class, () -> files.resource("/work/AGENTS.md")));
  }

  @Test
  void a_later_page_disappearing_does_not_return_the_instructions_prefix() {
    var failed = new SessionGoneException("file connection closed between pages");
    var requests = new ArrayList<FileRequest>();
    SessionChannel channel =
        (session, request) -> {
          requests.add(request);
          if (requests.size() > 1) throw failed;
          return io.aeyer.plowshare.protocol.FileReply.answered(
              request.id(), new Span(List.of("Required instructions"), 0, 2, true, Span.LINES));
        };
    var files = ChannelDefinitions.skills(channel, "client");
    assertSame(failed, assertThrows(SessionGoneException.class, () -> files.resource("AGENTS.md")));
    assertEquals(2, requests.size());
  }

  @Test
  void a_project_no_longer_rooted_after_reading_never_returns_an_assembled_rule_set() {
    var rooted = new AtomicBoolean(true);
    var files = new FakeFiles().withFile("AGENTS.md", "Required rules");
    SessionChannel channel =
        (session, request) -> {
          var reply = files.ask(session, request);
          rooted.set(false);
          return reply;
        };
    var rules =
        new AgentRules(
            DataLayout.NONE, channel, session -> true, (project, session) -> rooted.get());
    assertThrows(
        WorkspaceUnavailableException.class,
        () -> rules.forPath(new DefinitionResolver.Caller(7L, "client"), "worker", null));
  }

  @Test
  void unrelated_or_unrooted_sessions_never_read_another_projects_rules() {
    var requests = new ArrayList<FileRequest>();
    SessionChannel channel =
        (session, request) -> {
          requests.add(request);
          throw new AssertionError();
        };
    var rules =
        new AgentRules(
            DataLayout.NONE,
            channel,
            session -> true,
            (project, session) -> project == 7L && session.equals("owner"));
    assertTrue(rules.forPath(new DefinitionResolver.Caller(8L, "owner"), "worker", null).isEmpty());
    assertTrue(rules.forPath(new DefinitionResolver.Caller(7L, "other"), "worker", null).isEmpty());
    assertTrue(requests.isEmpty());
  }

  @Test
  void optional_environment_failure_still_preserves_the_last_known_caps_contract() {
    var failed = new SessionGoneException("file connection closed");
    SessionChannel channel =
        (session, request) -> {
          throw failed;
        };
    var read = new ChannelDefinitions(channel, "client").environmentFileRead();
    assertFalse(read.absent());
    assertNull(read.text());
    assertEquals(failed.getMessage(), read.unreadable());
  }
}
