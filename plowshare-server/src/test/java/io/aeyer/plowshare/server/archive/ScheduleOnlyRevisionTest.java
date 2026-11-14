package io.aeyer.plowshare.server.archive;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.server.faults.CallerFault;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScheduleOnlyRevisionTest {
  @TempDir Path directory;

  Path root(String name) throws Exception {
    Path root = Files.createDirectories(directory.resolve(name));
    Files.writeString(root.resolve("plowshare.json"), "{\"version\":1,\"name\":\"app\"}");
    return root;
  }

  void refused(Path current, Path proposed) {
    var refusal =
        assertThrows(
            CallerFault.class,
            () -> ScheduleOnlyRevision.requireOnlySchedulesChanged(current, proposed));
    assertEquals("A schedule-only revision may change only schedules/*.json", refusal.getMessage());
  }

  @Test
  void admitsAnAddedOrChangedScheduleOnly() throws Exception {
    Path current = root("current"), proposed = root("proposed");
    Files.createDirectories(proposed.resolve("schedules"));
    Files.writeString(proposed.resolve("schedules/daily.json"), "{}");
    ScheduleOnlyRevision.requireOnlySchedulesChanged(current, proposed);
    Files.writeString(proposed.resolve("plowshare.json"), "{\"version\":1,\"name\":\"other\"}");
    refused(current, proposed);
  }

  @Test
  void oversizedRootIsRefused() throws Exception {
    // 128 files in all, plowshare.json included, is the package limit; one more is refused.
    Path current = root("current"), proposed = root("proposed");
    Files.createDirectories(proposed.resolve("schedules"));
    for (int i = 0; i < 127; i++)
      Files.writeString(proposed.resolve("schedules/s" + i + ".json"), "{}");
    ScheduleOnlyRevision.requireOnlySchedulesChanged(current, proposed);
    Files.writeString(proposed.resolve("schedules/s127.json"), "{}");
    refused(current, proposed);
    // 128 KiB in all, even when every file is within its 64 KiB.
    Path large = root("large");
    Files.createDirectories(large.resolve("schedules"));
    for (int i = 0; i < 3; i++)
      Files.writeString(large.resolve("schedules/s" + i + ".json"), "x".repeat(50000));
    refused(current, large);
    // 64 KiB per file.
    Path big = root("big");
    Files.createDirectories(big.resolve("schedules"));
    Files.writeString(big.resolve("schedules/daily.json"), "x".repeat(65537));
    refused(current, big);
  }
}
