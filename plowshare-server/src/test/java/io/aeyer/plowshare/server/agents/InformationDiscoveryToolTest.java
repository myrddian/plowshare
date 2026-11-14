package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.Information;
import io.aeyer.plowshare.protocol.InformationDiscovery;
import io.aeyer.plowshare.server.information.InformationAccess;
import io.aeyer.plowshare.server.information.InformationActor;
import io.aeyer.plowshare.server.information.InformationCatalogue;
import io.aeyer.plowshare.server.information.InformationDiscoveryRepository.Reader;
import io.aeyer.plowshare.server.information.InformationDiscoveryService;
import io.aeyer.plowshare.server.information.InformationJobs;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class InformationDiscoveryToolTest {
  final InformationDiscoveryService service = mock(InformationDiscoveryService.class);
  final InformationJobs inputs = mock(InformationJobs.class);
  final Runnable validity = mock(Runnable.class);
  final Home home = Home.of("execution-home");

  @Test
  void scope_discovery_uses_acting_identity_and_retains_server_owned_confinement() {
    String principal = UUID.randomUUID().toString();
    var actor = InformationActor.contained(principal, "container");
    var tool =
        new InformationDiscoveryTool("scopes", () -> service)
            .forRun(actor, inputs, "cnv_fixture", home.project(), validity);
    when(service.scopes(any(), any()))
        .thenReturn(new InformationDiscovery.Scopes(List.of(), null, false));
    assertTrue(tool.run("{\"limit\":10}", home).contains("hasMore"));
    var reader = ArgumentCaptor.forClass(Reader.class);
    verify(service).scopes(reader.capture(), any());
    assertEquals(principal, reader.getValue().actor());
    assertEquals(home.project(), reader.getValue().boundaryHome());
    assertEquals("container", reader.getValue().audit().attribution());
    verify(inputs, times(2)).requireLog("cnv_fixture", "container");
    verify(validity).run();
  }

  @Test
  void unknown_identity_fields_and_malformed_queries_never_reach_the_service() {
    var tool =
        new InformationDiscoveryTool("scopes", () -> service)
            .forRun(InformationActor.of("reader"), inputs, "cnv_fixture", null, validity);
    for (String args :
        List.of(
            "{\"actor\":\"admin\"}",
            "{\"limit\":\"10\"}",
            "{\"limit\":51}",
            "{\"limit\":1,\"limit\":2}"))
      assertTrue(tool.run(args, home).toLowerCase().contains("invalid"));
    verifyNoInteractions(service);
  }

  @Test
  void exact_discovered_read_does_not_resolve_the_execution_home_as_its_read_selection() {
    var access = mock(InformationAccess.class);
    var catalogue = mock(InformationCatalogue.class);
    UUID scope = UUID.randomUUID(), revision = UUID.randomUUID();
    when(service.read(any(), any()))
        .thenReturn(
            new Information.Window(revision, null, null, 0, 4, 4, "text", "document", "text"));
    var tool =
        new InformationTool(false, () -> catalogue)
            .withDiscovery(() -> service, null)
            .forRun(access, inputs, "reader", "cnv_fixture")
            .withQueryValidity(validity);
    String result =
        tool.run(
            "{\"operation\":\"read\",\"scopes\":[\""
                + scope
                + "\"],\"revision\":\""
                + revision
                + "\"}",
            home);
    assertTrue(result.contains("text"));
    var reader = ArgumentCaptor.forClass(Reader.class);
    verify(service)
        .read(
            reader.capture(),
            eq(new InformationDiscovery.Read(List.of(scope.toString()), revision, null, null)));
    assertNull(reader.getValue().boundaryHome());
    verifyNoInteractions(access, catalogue);
  }
}
