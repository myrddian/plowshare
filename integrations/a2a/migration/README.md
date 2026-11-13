# Preserve historical receiving provenance before cutover

This is an operator migration recipe for the archived **pre-cutover** Java SDK.
It is not part of the live adapter's classpath. Export before removing the legacy
`incoming.status` operation, keep the resulting private bundle, apply the explicit
native channel-adoption mapping, and import into the new receiving journal.
Neither step submits messages, guesses contexts or reads a database directly.

Use an archived distribution whose public `IncomingClient` and `Incoming.Source`
contracts match the old server. Supply its exact jar/dependency classpath yourself;
do not substitute the new SDK after legacy retirement. The service credential must
identify the original native principal. The exporter checks the authenticated
`admin.status` handle against the explicit principal before reading any task. Use
the same identity in the bundle and new receiving configuration.
The principal label does not grant ownership: both old status and new native
inspection enforce authenticated task/client/project scope on the server.

Save the following as `LegacyProvenanceExport.java` in a private working directory.
Its network operations are the existing authenticated SDK administration-status
read and task-status reads for the explicit client/task pairs. Supply the old bearer through `PLOWSHARE_OLD_TOKEN`,
never an argument or source file.

```java
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.aeyer.plowshare.protocol.Incoming;
import io.aeyer.plowshare.sdk.IncomingClient;
import io.aeyer.plowshare.sdk.AdministrationClient;
import io.aeyer.plowshare.sdk.Plowshare;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

class LegacyProvenanceExport {
  record Row(
      String client,
      UUID id,
      UUID context,
      String agent,
      String message,
      Instant createdAt,
      Incoming.Source source,
      boolean originalContextKnown) {}

  record Bundle(
      String schema, String origin, String project, String principal, List<Row> tasks) {}

  public static void main(String[] args) throws Exception {
    if (args.length < 5 || args.length > 260) {
      throw new IllegalArgumentException(
          "ORIGIN PROJECT PRINCIPAL PRIVATE_OUTPUT CLIENT:TASK_UUID...");
    }
    String token = System.getenv("PLOWSHARE_OLD_TOKEN");
    if (token == null || token.isBlank()) {
      throw new IllegalArgumentException("Explicit old service credential required");
    }
    var rows = new ArrayList<Row>();
    try (var sdk = Plowshare.connect(args[0], token, Duration.ofSeconds(30), null)) {
      if (!args[2].equals(new AdministrationClient(sdk).status().handle())) {
        throw new IllegalArgumentException("Authenticated old principal differs from export identity");
      }
      var incoming = new IncomingClient(sdk);
      for (int index = 4; index < args.length; index++) {
        var pair = args[index].split(":", 2);
        if (pair.length != 2 || !pair[0].matches("[A-Za-z0-9_.-]{1,64}")) {
          throw new IllegalArgumentException("Explicit client/task pair required");
        }
        UUID id = UUID.fromString(pair[1]);
        var task = incoming.status(new Incoming.Id(args[1], pair[0], id));
        rows.add(
            new Row(
                pair[0], task.id(), task.context(), task.agent(), task.message(),
                task.createdAt(), task.source(), task.source() != null));
      }
    }
    var json =
        new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    byte[] bytes =
        json.writeValueAsBytes(
            new Bundle(
                "plowshare-a2a-provenance/1", args[0], args[1], args[2], List.copyOf(rows)));
    if (bytes.length > 64 * 1024 * 1024) {
      throw new IllegalArgumentException("Split the explicit task list into smaller bundles");
    }
    try (var output =
        FileChannel.open(
            Path.of(args[3]),
            Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
      var buffer = ByteBuffer.wrap(bytes);
      while (buffer.hasRemaining()) output.write(buffer);
      output.force(true);
    }
  }
}
```

Launch with Java21 and the archived SDK classpath; addresses, identities and paths
below are explicit placeholders:

```sh
java --class-path "$PLOWSHARE_OLD_SDK_CLASSPATH" LegacyProvenanceExport.java \
  https://old-server.example.invalid receiving-project VERIFIED_NATIVE_PRINCIPAL \
  /ABSOLUTE_PRIVATE_DIRECTORY/provenance.json CLIENT:KNOWN_TASK_UUID
```

Use 1–256 known task/client pairs per bundle. The output is created mode0600 and
never overwrites an existing file. Store its directory mode0700 and back it up
with the old deployment. A failed export is not a complete bundle; never infer
missing tasks or manufacture a source record to fill one in. Avoid printing the
bundle or old SDK errors containing server responses into public logs.

The new importer is an explicit local CLI command, after native channel adoption:

```sh
plowshare-a2a NEW_CONFIG import-provenance /ABSOLUTE_PRIVATE_DIRECTORY/provenance.json
```

The importer validates the private schema, origin, project and configured
principal, verifies the authenticated `admin.status` handle, then performs fresh native inspection for every exact client/task.
Task/context/agent/native-message/creation identity must match before any source
is imported. It retains the exact A2A Source separately from native execution
records. It creates no fictitious receive publication, source event or accepted
mutation. Repeating an import is idempotent only for equal historical evidence;
changed source or ownership conflicts.

A known historical message repeated over HTTP resolves its imported identity and
performs read-only native inspection. It never issues a fresh receive. If the old
server did not record source/original-context evidence, that absence is retained
explicitly and fresh admission for the affected client is fenced rather than
assuming a new conversation. Reads and explicit task cancellation remain available.
Choose a deliberately new trusted client/binding for new work when the original
request identity cannot be established; do not reuse an uncertain old identity.

Operation/body/import evidence occupies the adapter's durable private journal.
Interrupted import writes leave an explicit pending marker; restart the same
import after inspection, rather than running a listener against partial migration.
Runtime startup refuses that marker. Retire a binding only after its historical
and current work has been reconciled. This recipe is not evidence that a live
production export/import or production-data rehearsal has occurred.
