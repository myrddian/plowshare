package io.aeyer.plowshare.server.relay;

/**
 * Transactional owning-domain observer for explicitly configured execution exports. Invocation is
 * before broker append and inside the publication ledger transaction: a refusal leaves neither an
 * export nor a native request. Ordinary publications have no domain effect unless opted in.
 */
@FunctionalInterface
public interface ExecutionPublicationObserver {
  ExecutionPublicationObserver NONE = intent -> {};

  void beforeAppend(RelayPublishingRepository.Intent intent);
}
