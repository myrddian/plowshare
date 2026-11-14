package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.InformationDiscovery;
import io.aeyer.plowshare.server.agents.JobStore;
import io.aeyer.plowshare.server.documents.Deliberation;
import io.aeyer.plowshare.server.documents.DocumentStore;
import io.aeyer.plowshare.server.documents.DocumentsProperties;
import io.aeyer.plowshare.server.documents.RetrievalService;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.fetch.PageFetcher;
import io.aeyer.plowshare.server.information.InformationAccess;
import io.aeyer.plowshare.server.information.InformationCatalogue;
import io.aeyer.plowshare.server.information.InformationContentSettings;
import io.aeyer.plowshare.server.information.InformationContentTypes;
import io.aeyer.plowshare.server.information.InformationDiscoveryRepository.Reader;
import io.aeyer.plowshare.server.information.InformationDiscoveryService;
import io.aeyer.plowshare.server.information.InformationInterchangeService;
import io.aeyer.plowshare.server.information.InformationNavigationService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class InformationDiscoveryFramesTest {
  final InformationDiscoveryService discovery = mock(InformationDiscoveryService.class);
  final InformationAccess legacyAccess = mock(InformationAccess.class);
  final InformationFrames frames =
      new InformationFrames(
          mock(InformationCatalogue.class),
          legacyAccess,
          mock(DocumentStore.class),
          mock(RetrievalService.class),
          mock(Deliberation.class),
          mock(JobStore.class),
          mock(DocumentsProperties.class),
          mock(PageFetcher.class),
          mock(InformationContentTypes.class),
          mock(InformationContentSettings.class),
          mock(InformationNavigationService.class),
          mock(InformationInterchangeService.class));

  @Test
  void explicit_discovery_scopes_use_the_socket_identity_and_no_legacy_home_default() {
    frames.useDiscovery(discovery);
    String scope = UUID.randomUUID().toString();
    var query = new InformationDiscovery.Query(List.of(scope), null, null, null, 10, null);
    when(discovery.list(any(), eq(query)))
        .thenReturn(new InformationDiscovery.Page(List.of(scope), List.of(), null, false));
    var answer =
        frames
            .frames()
            .get("information.discovery.list")
            .handle(Map.of("scopes", List.of(scope), "limit", 10), new Asking("socket", "reader"));
    assertInstanceOf(InformationDiscovery.Page.class, answer.payload());
    var reader = ArgumentCaptor.forClass(Reader.class);
    verify(discovery).list(reader.capture(), eq(query));
    assertEquals("reader", reader.getValue().actor());
    assertNull(reader.getValue().boundaryHome());
    assertNull(reader.getValue().audit());
    verifyNoInteractions(legacyAccess);
  }

  @Test
  void identity_and_ceiling_fields_are_rejected_before_discovery() {
    frames.useDiscovery(discovery);
    for (String field : List.of("actor", "boundaryHome", "scope", "project", "audit")) {
      assertThrows(
          CallerFault.class,
          () ->
              frames
                  .frames()
                  .get("information.discovery.scopes")
                  .handle(Map.of(field, "foreign"), new Asking("socket", "reader")));
    }
    verifyNoInteractions(discovery, legacyAccess);
  }
}
