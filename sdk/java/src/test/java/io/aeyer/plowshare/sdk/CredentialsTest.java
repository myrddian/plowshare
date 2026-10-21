package io.aeyer.plowshare.sdk;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.sdk.UserAuthentication.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CredentialsTest {
  @TempDir Path root;
  private static final String ORIGIN = "https://auth.example";

  private static class Authority implements UserAuthentication {
    final AtomicInteger rotations = new AtomicInteger();

    @Override
    public String origin() {
      return ORIGIN;
    }

    @Override
    public SignIn login(String handle, String password) {
      return new SignIn(new Tokens("access0", "refresh0"), false, false);
    }

    @Override
    public Standing standing(Tokens tokens) throws IOException {
      return new Standing(!tokens.access().equals("access0"), false);
    }

    @Override
    public Tokens refresh(Tokens tokens) throws IOException {
      rotations.incrementAndGet();
      return new Tokens("access1", "refresh1");
    }

    @Override
    public void logout(Tokens tokens) throws IOException {}
  }

  private Credentials store() {
    return new Credentials(ORIGIN, "alice", root, Duration.ofSeconds(2));
  }

  private Path file() {
    return root.resolve("connections")
        .resolve(Credentials.connectionKey(ORIGIN, "alice"))
        .resolve("credentials")
        .resolve(AuthState.hash(ORIGIN) + ".json");
  }

  @Test
  void concurrent_rejection_rotates_once_and_persists_before_return() throws Exception {
    var store = store();
    var auth = new Authority();
    store.login("alice", "fixture-password", auth);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var results = new java.util.ArrayList<Future<Credentials.UserSession>>();
      for (int i = 0; i < 6; i++) results.add(executor.submit(() -> store.authenticated(auth)));
      for (var result : results) assertEquals("access1", result.get().tokens().access());
    }
    assertEquals(1, auth.rotations.get());
    assertEquals("access1", store.session().tokens().access());
    assertFalse(store.session().toString().contains("access1"));
  }

  @Test
  void cancelled_and_failed_refresh_remain_fenced_even_for_older_access() throws Exception {
    for (boolean interrupt : new boolean[] {false, true}) {
      var store = store();
      var auth =
          new Authority() {
            @Override
            public Tokens refresh(Tokens tokens) throws IOException {
              rotations.incrementAndGet();
              if (interrupt) {
                Thread.currentThread().interrupt();
                return new Tokens("new", "new-refresh");
              }
              throw new IOException("private diagnostic");
            }
          };
      store.login("alice", "fixture-password", auth);
      try {
        var error = assertThrows(Credentials.LoginRequired.class, () -> store.renew(auth, null));
        assertFalse(error.getMessage().contains("private diagnostic"));
      } finally {
        Thread.interrupted();
      }
      assertThrows(Credentials.LoginRequired.class, () -> store.renew(auth, "older"));
      assertEquals(1, auth.rotations.get());
    }
  }

  @Test
  void failed_persistence_keeps_lock_and_does_not_expose_pair() throws Exception {
    var store = store();
    var auth =
        new Authority() {
          @Override
          public Tokens refresh(Tokens tokens) throws IOException {
            Files.delete(file());
            Files.createDirectory(file());
            return new Tokens("new", "new-refresh");
          }
        };
    store.login("alice", "fixture-password", auth);
    assertThrows(IOException.class, () -> store.renew(auth, null));
    assertTrue(Files.isDirectory(file().resolveSibling(file().getFileName() + ".lock")));
  }

  @Test
  void unavailable_status_does_not_renew_or_select_another_account() throws Exception {
    var store = store();
    var auth =
        new Authority() {
          @Override
          public Standing standing(Tokens tokens) throws IOException {
            throw new IOException("Unavailable");
          }
        };
    store.login("alice", "fixture-password", auth);
    assertThrows(IOException.class, () -> store.authenticated(auth));
    assertEquals(0, auth.rotations.get());
    assertThrows(IOException.class, () -> store.login("bob", "password", auth));
  }

  @Test
  void state_boundary_refuses_service_credentials_null_fields_and_symlinks() throws Exception {
    var store = store();
    store.login("alice", "fixture-password", new Authority());
    String saved = Files.readString(file());
    for (String malformed :
        new String[] {
          saved.replace("access0", "pss_service"),
          saved.replace("\"renewing\":false", "\"renewing\":null"),
          saved.replace("\"version\":1", "\"version\":true"),
          saved.replace("\"refresh\":\"refresh0\"", "\"refresh\":null")
        }) {
      Files.writeString(file(), malformed);
      assertThrows(IOException.class, store::session);
    }
    Files.delete(file());
    Path target = root.resolve("target");
    Files.writeString(target, saved);
    Files.createSymbolicLink(file(), target);
    assertThrows(IOException.class, store::session);
  }

  @Test
  void canonical_keys_agree_and_invalid_origins_never_select_another_server() {
    assertEquals(
        Credentials.connectionKey("https://example.com", "alice"),
        Credentials.connectionKey("https://EXAMPLE.com:443/", "alice"));
    for (String origin :
        new String[] {
          "https://user@example.com",
          "https://example.com/path",
          "https://é.example",
          "https://example.com?"
        })
      assertThrows(IllegalArgumentException.class, () -> new Credentials(origin, "alice", root));
  }
}
