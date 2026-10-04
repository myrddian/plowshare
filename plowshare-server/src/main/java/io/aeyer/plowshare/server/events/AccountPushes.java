package io.aeyer.plowshare.server.events;

/** Server-to-client bodies for every socket signed in as one account. Never throws. */
@FunctionalInterface
public interface AccountPushes {

  AccountPushes NONE = (handle, body) -> {};

  void push(String handle, Object body);
}
