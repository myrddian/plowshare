package io.aeyer.plowshare.integrations.ha;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.OperationBudget;
import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.sdk.RelayClient;
import io.aeyer.plowshare.sdk.RequestCancellation;
import io.aeyer.plowshare.sdk.RequestOptions;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class HomeAssistantToolTransportTest {
  @Test
  void publicSdkRequestsUseRemainingBudgetAndExplicitNoCapacityReplay() throws Exception {
    RelayClient client = mock(RelayClient.class);
    var selected = new AtomicReference<RequestOptions>();
    var request =
        new RelayPort.Publish(
            "11111111-1111-1111-1111-111111111111",
            "fixture",
            "tool.fixture.catalog",
            "fixture",
            Instant.now(),
            null,
            null,
            null);
    when(client.publishAsync(any(RelayPort.Publish.class), any(RequestOptions.class)))
        .thenAnswer(
            call -> {
              selected.set(call.getArgument(1, RequestOptions.class));
              return CompletableFuture.completedFuture(
                  new RelayPort.Published(
                      request.requestId(), request.project(), request.topic(), "1", Instant.now()));
            });
    var cancellation = new RequestCancellation();
    var transport = new HomeAssistantToolTransport.Sdk(client);
    transport.publish(request, OperationBudget.start(Duration.ofSeconds(2)), cancellation);
    assertEquals(RequestOptions.Capacity.NON_BLOCKING, selected.get().capacity());
    assertTrue(selected.get().timeout().compareTo(Duration.ofSeconds(2)) <= 0);
    assertTrue(selected.get().cancellation() == cancellation);
    verify(client, times(1)).publishAsync(any(RelayPort.Publish.class), any(RequestOptions.class));
  }

  @Test
  void timeoutCancelsOnlyThisSdkInterestWithoutIssuingAReplacement() throws Exception {
    RelayClient client = mock(RelayClient.class);
    when(client.publishAsync(any(RelayPort.Publish.class), any(RequestOptions.class)))
        .thenReturn(new CompletableFuture<>());
    var request =
        new RelayPort.Publish(
            "11111111-1111-1111-1111-111111111111",
            "fixture",
            "tool.fixture.catalog",
            "fixture",
            Instant.now(),
            null,
            null,
            null);
    var cancellation = new RequestCancellation();
    assertThrows(
        IOException.class,
        () ->
            new HomeAssistantToolTransport.Sdk(client)
                .publish(request, OperationBudget.start(Duration.ofMillis(10)), cancellation));
    assertTrue(cancellation.isCancelled());
    verify(client, times(1)).publishAsync(any(RelayPort.Publish.class), any(RequestOptions.class));
  }
}
