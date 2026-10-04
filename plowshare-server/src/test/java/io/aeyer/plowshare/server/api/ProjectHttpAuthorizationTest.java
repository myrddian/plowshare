package io.aeyer.plowshare.server.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.server.access.*;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.auth.AdminStore;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;

class ProjectHttpAuthorizationTest {
  AtomicInteger executions;
  MockMvc mvc;

  @RestController
  static class Endpoints {
    final AtomicInteger executions;

    Endpoints(AtomicInteger executions) {
      this.executions = executions;
    }

    record Body(String project, String task) {}

    @PostMapping("/v1/agents/{name}/runs")
    String run(@RequestBody Body body) {
      executions.incrementAndGet();
      return "started";
    }

    @PostMapping("/v1/agents")
    String define(@RequestBody Body body) {
      executions.incrementAndGet();
      return "defined";
    }

    @GetMapping("/v1/conversations/{id}/turns")
    String read() {
      executions.incrementAndGet();
      return "read";
    }
  }

  @BeforeEach
  void setup() {
    var jdbc = mock(JdbcTemplate.class);
    when(jdbc.getDataSource()).thenReturn(mock(javax.sql.DataSource.class));
    when(jdbc.queryForList(anyString(), eq(String.class), eq("cnv_project")))
        .thenReturn(List.of("integration"));
    var members =
        new ProjectMembers(jdbc) {
          @Override
          public Optional<ProjectRole> role(String project, String handle) {
            if (!"integration".equals(project)) return Optional.empty();
            return switch (handle) {
              case "viewer" -> Optional.of(ProjectRole.VIEWER);
              case "contributor" -> Optional.of(ProjectRole.CONTRIBUTOR);
              case "manager" -> Optional.of(ProjectRole.MANAGER);
              default -> Optional.empty();
            };
          }
        };
    var authorization = new ProjectAuthorization(jdbc, members, mock(AdminStore.class));
    var http = new ProjectHttpAuthorization(authorization, new ObjectMapper());
    executions = new AtomicInteger();
    mvc =
        MockMvcBuilders.standaloneSetup(new Endpoints(executions))
            .setControllerAdvice(http)
            .addInterceptors(http)
            .build();
  }

  @Test
  void viewer_reads_existing_ids_but_cannot_start_work() throws Exception {
    mvc.perform(
            get("/v1/conversations/cnv_project/turns").requestAttr("plowshare.handle", "viewer"))
        .andExpect(status().isOk());
    assertEquals(1, executions.get());
    assertThrows(
        jakarta.servlet.ServletException.class,
        () ->
            mvc.perform(
                post("/v1/agents/bot/runs")
                    .requestAttr("plowshare.handle", "viewer")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"project\":\"integration\",\"task\":\"work\"}")));
    assertEquals(1, executions.get());
  }

  @Test
  void contributor_runs_work_but_cannot_redefine_agents() throws Exception {
    mvc.perform(
            post("/v1/agents/bot/runs")
                .requestAttr("plowshare.handle", "contributor")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"project\":\"integration\",\"task\":\"work\"}"))
        .andExpect(status().isOk());
    assertThrows(
        jakarta.servlet.ServletException.class,
        () ->
            mvc.perform(
                post("/v1/agents")
                    .requestAttr("plowshare.handle", "contributor")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"project\":\"integration\"}")));
    assertEquals(1, executions.get());
  }

  @Test
  void project_manager_can_define_agents_and_nonmembers_cannot_read_by_id() throws Exception {
    mvc.perform(
            post("/v1/agents")
                .requestAttr("plowshare.handle", "manager")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"project\":\"integration\"}"))
        .andExpect(status().isOk());
    assertThrows(
        jakarta.servlet.ServletException.class,
        () ->
            mvc.perform(
                get("/v1/conversations/cnv_project/turns")
                    .requestAttr("plowshare.handle", "stranger")));
    assertEquals(1, executions.get());
  }
}
