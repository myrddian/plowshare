package io.aeyer.plowshare.server.events;

import io.aeyer.plowshare.protocol.AccountEvent;

/** Server-to-client bodies for every socket signed in as one account. Never throws. */
@FunctionalInterface
public interface AccountPushes {

  AccountPushes NONE = (handle, body) -> {};

  void push(String handle, AccountEvent body);
}
