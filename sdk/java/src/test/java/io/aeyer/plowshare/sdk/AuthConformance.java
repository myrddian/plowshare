package io.aeyer.plowshare.sdk;

import java.nio.file.Path;
import java.time.Duration;

/** Isolated synthetic HTTP/file interoperability driver, never a user CLI. */
public final class AuthConformance {
  private AuthConformance() {}

  private record Input(
      String Root,
      String Origin,
      String Action,
      String Rejected,
      String Account,
      String Connection) {}

  private record Output(
      String access, String handle, String key, boolean failed, boolean loginRequired) {}

  public static void main(String[] args) throws Exception {
    var json = new com.fasterxml.jackson.databind.ObjectMapper();
    var input = json.readValue(System.in, Input.class);
    Output out;
    try {
      String account = input.Account().isEmpty() ? "alice" : input.Account();
      if (input.Action().equals("key")) {
        System.out.println(
            json.writeValueAsString(
                new Output(
                    null,
                    account,
                    Credentials.connectionKey(input.Origin(), account),
                    false,
                    false)));
        return;
      }
      var store =
          input.Action().equals("resolve")
              ? Credentials.resolve(
                  empty(input.Origin()),
                  empty(input.Account()),
                  empty(input.Connection()),
                  (input.Root().isEmpty() ? null : Path.of(input.Root())))
              : new Credentials(
                  input.Origin(),
                  account,
                  (input.Root().isEmpty() ? null : Path.of(input.Root())),
                  Duration.ofSeconds(2));
      switch (input.Action()) {
        case "login" -> store.login(account, "fixture-password", null);
        case "renew" -> store.renew(null, empty(input.Rejected()));
        case "authenticated" -> store.authenticated(null);
        case "logout" -> store.logout(null);
        default -> {}
      }
      if (input.Action().equals("logout")) out = new Output(null, null, null, false, false);
      else {
        var session = store.session();
        out =
            new Output(
                session.tokens().access(),
                session.handle(),
                Credentials.connectionKey(session.origin(), session.handle()),
                false,
                false);
      }
    } catch (java.io.IOException | IllegalArgumentException refused) {
      out = new Output(null, null, null, true, refused instanceof Credentials.LoginRequired);
    }
    System.out.println(json.writeValueAsString(out));
  }

  private static String empty(String value) {
    return value.isEmpty() ? null : value;
  }
}
