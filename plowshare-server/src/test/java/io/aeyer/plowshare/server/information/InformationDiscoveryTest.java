package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.InformationDiscovery;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.information.InformationDiscoveryRepository.Reader;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class InformationDiscoveryTest {
  final InformationDiscoveryRepository repository = mock(InformationDiscoveryRepository.class);
  final Clock clock = mock(Clock.class);
  final InformationDiscoveryService service = new CursorInformationDiscovery(repository, clock);
  final Reader reader = new Reader("reader", null, null);
  final String scope = UUID.randomUUID().toString();
  final Instant time = Instant.parse("2026-11-14T00:00:00Z");

  InformationDiscovery.Item item() {
    return new InformationDiscovery.Item(
        UUID.randomUUID(),
        UUID.randomUUID(),
        scope,
        "research",
        "Evidence",
        "Evidence",
        "source",
        null,
        time,
        List.of(),
        List.of(),
        "reader",
        "text",
        false);
  }

  InformationDiscovery.Query query(String cursor) {
    return new InformationDiscovery.Query(List.of(scope), null, null, null, 1, cursor);
  }

  @Test
  void keyset_continuation_binds_query_actor_and_confinement_and_requeries_live_repository() {
    when(clock.instant()).thenReturn(time);
    var first = item();
    when(repository.list(eq(reader), any(), any(), isNull(), eq(2)))
        .thenReturn(List.of(first, item()));
    var page = service.list(reader, query(null));
    assertEquals(List.of(first), page.items());
    assertTrue(page.hasMore());
    when(repository.list(eq(reader), any(), any(), notNull(), eq(2))).thenReturn(List.of());
    clearInvocations(repository);
    var last = service.list(reader, query(page.nextCursor()));
    assertFalse(last.hasMore());
    assertNull(last.nextCursor());
    var position = ArgumentCaptor.forClass(InformationDiscoveryRepository.Position.class);
    verify(repository).list(eq(reader), any(), any(), position.capture(), eq(2));
    assertEquals(
        new InformationDiscoveryRepository.Position(time, first.revision().toString()),
        position.getValue());
  }

  @Test
  void cursors_refuse_other_principals_scopes_operations_tampering_and_expiry_before_reads() {
    when(clock.instant()).thenReturn(time);
    when(repository.list(any(), any(), any(), any(), anyInt())).thenReturn(List.of(item(), item()));
    String cursor = service.list(reader, query(null)).nextCursor();
    clearInvocations(repository);
    assertThrows(
        CallerFault.class, () -> service.list(new Reader("other", null, null), query(cursor)));
    assertThrows(
        CallerFault.class,
        () -> service.list(new Reader("reader", "research", null), query(cursor)));
    assertThrows(
        CallerFault.class,
        () ->
            service.list(
                reader,
                new InformationDiscovery.Query(
                    List.of(UUID.randomUUID().toString()), null, null, null, 1, cursor)));
    assertThrows(
        CallerFault.class,
        () ->
            service.search(
                reader,
                new InformationDiscovery.Query(List.of(scope), null, "query", null, 1, cursor)));
    String tampered = (cursor.charAt(0) == 'A' ? "B" : "A") + cursor.substring(1);
    assertThrows(CallerFault.class, () -> service.list(reader, query(tampered)));
    when(clock.instant()).thenReturn(time.plusSeconds(900));
    assertThrows(CallerFault.class, () -> service.list(reader, query(cursor)));
    verifyNoInteractions(repository);
  }

  @Test
  void text_windows_use_utf16_positions_and_do_not_invent_readiness() {
    UUID revision = UUID.randomUUID();
    var request = new InformationDiscovery.Read(List.of(scope), revision, 1, 2);
    when(repository.read(reader, request))
        .thenReturn(new InformationDiscoveryRepository.Text(revision, "A😀B", "document", "text"));
    var window = service.read(reader, request);
    assertEquals("😀", window.text());
    assertEquals(3, window.end());
    assertEquals(4, window.total());
    when(repository.read(reader, request))
        .thenReturn(new InformationDiscoveryRepository.Text(revision, null, "document", "text"));
    assertThrows(CallerFault.class, () -> service.read(reader, request));
  }

  @Test
  void malformed_and_identity_forging_payloads_fail_before_repository_access() {
    for (var fields :
        List.of(
            Map.<String, Object>of("scopes", List.of(scope), "owner", "admin"),
            Map.<String, Object>of("scopes", List.of(scope, scope)),
            Map.<String, Object>of("scopes", List.of(scope), "limit", 51),
            Map.<String, Object>of("scopes", List.of(scope), "limit", "10"),
            Map.<String, Object>of("scopes", List.of(scope), "cursor", "bad"),
            Map.<String, Object>of("scopes", List.of(scope), "filter", Map.of("unknown", "x"))))
      assertThrows(CallerFault.class, () -> InformationDiscoveryInputs.query("list", fields));
    verifyNoInteractions(repository);
  }
}
