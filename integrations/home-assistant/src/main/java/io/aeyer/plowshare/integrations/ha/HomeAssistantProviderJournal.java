package io.aeyer.plowshare.integrations.ha;

import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.sdk.RelayTools;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

/**
 * Exclusive provider persistence. Every save commits durably before returning; no intent replay.
 */
public interface HomeAssistantProviderJournal extends RelayTools.Journal, AutoCloseable {
  int MAX_CATALOGUE_TEXT = 128 * 1024;

  /** Stable broker coordinates, independent of a retry's consumer/batch token. */
  record Delivery(String invocationId, String topic, String generation, String position) {
    public Delivery {
      RelayPort.uuid(invocationId);
      RelayPort.topic(topic);
      if (generation != null) RelayPort.uuid(generation);
      if (position == null || !position.matches("[1-9][0-9]{0,18}"))
        throw new IllegalArgumentException("Invalid HA request position");
      try {
        Long.parseLong(position);
      } catch (NumberFormatException invalid) {
        throw new IllegalArgumentException("Invalid HA request position");
      }
    }
  }

  /** Reject retired broker positions or changed cursor incarnations before another handler. */
  void checkDelivery(Delivery delivery) throws IOException;

  /** Called only after the SDK validated/persisted this immutable execution intent. */
  void captureDelivery(Delivery delivery) throws IOException;

  /** Caller first validates the exact broker acknowledgement and its issued boundary. */
  void acknowledged(Delivery delivery) throws IOException;

  /** The exact SDK-created publication is retained before it can leave this process. */
  record CatalogueIntent(RelayPort.Publish request, boolean withdrawal) {
    public CatalogueIntent {
      Objects.requireNonNull(request);
      if (request.text().length() > MAX_CATALOGUE_TEXT
          || request.correlationId() != null
          || request.parentTopic() != null
          || request.parentEventId() != null)
        throw new IllegalArgumentException("Invalid HA catalogue intent");
    }
  }

  record CatalogueConfirmed(RelayPort.Published receipt, boolean withdrawal) {
    public CatalogueConfirmed {
      Objects.requireNonNull(receipt);
    }
  }

  Optional<CatalogueIntent> publishing() throws IOException;

  Optional<CatalogueConfirmed> confirmed() throws IOException;

  void beginCatalogue(RelayPort.Publish publication, boolean withdrawal) throws IOException;

  void confirmCatalogue(RelayPort.Published receipt) throws IOException;

  void notSubmittedCatalogue(String requestId) throws IOException;

  @Override
  void close() throws IOException;
}
