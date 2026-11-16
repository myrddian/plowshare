package io.aeyer.plowshare.server.approvals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileApprovalPolicyRepositoryTest {
  @TempDir Path temporary;

  @Test
  void absentPolicyIsManualAndOwnersHaveIndependentRevisions() throws IOException {
    var policies = repository(Duration.ofSeconds(2));
    assertEquals(ApprovalPolicy.manual(), policies.read("alice"));
    assertEquals(1, policies.save("alice", smart(0)).revision());
    assertEquals(ApprovalPolicy.Mode.SMART, policies.read("alice").mode());
    assertEquals(ApprovalPolicy.manual(), policies.read("bob"));
    assertThrows(IllegalStateException.class, () -> policies.save("alice", smart(0)));
    assertEquals(2, policies.save("alice", smart(1)).revision());
    assertEquals(2, repository(Duration.ofSeconds(2)).read("alice").revision());
  }

  @Test
  void concurrentWritersCannotBothReplaceTheSameRevision() throws Exception {
    var policies = repository(Duration.ofSeconds(2));
    Callable<Boolean> save =
        () -> {
          try {
            policies.save("alice", smart(0));
            return true;
          } catch (IllegalStateException conflict) {
            return false;
          }
        };
    try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      var results = workers.invokeAll(List.of(save, save));
      int successful = 0;
      for (var result : results) if (result.get()) successful++;
      assertEquals(1, successful);
    }
    assertEquals(1, policies.read("alice").revision());
  }

  @Test
  void malformedPolicyNeverReturnsAStaleAutomaticAllow() throws IOException {
    var policies = repository(Duration.ofSeconds(2));
    policies.save("alice", smart(0));
    Path file = temporary.toRealPath().resolve("alice/approval-policy.json");
    for (String invalid :
        List.of(
            "null",
            "{}",
            "{\"revision\":1,\"mode\":\"SMART\",\"bot\":null,\"rules\":[],\"extra\":true}",
            "{\"revision\":1.5,\"mode\":\"SMART\",\"bot\":null,\"rules\":[]}",
            "{\"revision\":1,\"mode\":\"SMART\",\"mode\":\"MANUAL\",\"bot\":null,\"rules\":[]}")) {
      Files.writeString(file, invalid);
      assertThrows(IllegalStateException.class, () -> policies.read("alice"));
    }
    Files.writeString(file, " ".repeat(1024 * 1024 + 1));
    assertThrows(IllegalStateException.class, () -> policies.read("alice"));
  }

  @Test
  void policyAndDirectoryLinksAreNotFollowed() throws IOException {
    var policies = repository(Duration.ofSeconds(2));
    Path root = temporary.toRealPath();
    Files.createDirectories(root.resolve("alice"));
    Path privateFile = Files.writeString(root.resolve("elsewhere.json"), "private");
    Files.createSymbolicLink(root.resolve("alice/approval-policy.json"), privateFile);
    assertThrows(IllegalStateException.class, () -> policies.read("alice"));
    assertThrows(IllegalStateException.class, () -> policies.save("alice", smart(0)));
    assertEquals("private", Files.readString(privateFile));
    Files.createSymbolicLink(root.resolve("bob"), root.resolve("alice"));
    assertThrows(IllegalStateException.class, () -> policies.read("bob"));
  }

  @Test
  void lockWaitHasOneFiniteBudgetAndDoesNotPublishAfterExpiry() throws IOException {
    var policies = repository(Duration.ofMillis(30));
    Path directory = Files.createDirectories(temporary.toRealPath().resolve("alice"));
    try (var channel =
            FileChannel.open(
                directory.resolve("approval-policy.lock"),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
        var lock = channel.lock()) {
      assertEquals(true, lock.isValid());
      assertTimeoutPreemptively(
          Duration.ofSeconds(2),
          () -> assertThrows(IllegalStateException.class, () -> policies.save("alice", smart(0))));
      assertEquals(false, Files.exists(directory.resolve("approval-policy.json")));
    }
  }

  private ApprovalPolicyRepository repository(Duration budget) throws IOException {
    Path root = temporary.toRealPath();
    return new FileApprovalPolicyRepository(owner -> root.resolve(owner), budget);
  }

  private static ApprovalPolicy smart(long revision) {
    return new ApprovalPolicy(revision, ApprovalPolicy.Mode.SMART, null, List.of());
  }
}
