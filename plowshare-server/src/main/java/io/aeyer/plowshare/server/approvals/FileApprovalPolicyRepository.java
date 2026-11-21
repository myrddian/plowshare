package io.aeyer.plowshare.server.approvals;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Function;

/**
 * Private Personal settings, outside the exposed workspace tree. An OS-owning operator remains a
 * trusted filesystem writer; this is not a sandbox against unrestricted host processes.
 * Participating server writers use a bounded file lock and revision check. No permissive cache
 * survives bad files.
 */
public final class FileApprovalPolicyRepository implements ApprovalPolicyRepository {
  private static final int MAX_BYTES = 1024 * 1024;
  private static final ObjectMapper JSON = strictMapper();

  static ObjectMapper strictMapper() {
    ObjectMapper mapper =
        JsonMapper.builder()
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(
                DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .build();
    mapper
        .coercionConfigFor(LogicalType.Textual)
        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
    return mapper;
  }

  private final Function<String, Path> directories;
  private final Duration budget;

  public FileApprovalPolicyRepository(Function<String, Path> directories, Duration budget) {
    this.directories = Objects.requireNonNull(directories);
    this.budget = Objects.requireNonNull(budget);
    if (budget.isNegative() || budget.isZero() || budget.compareTo(Duration.ofSeconds(30)) > 0)
      throw new IllegalArgumentException(
          "Policy IO budget must be positive and at most 30 seconds");
  }

  @Override
  public ApprovalPolicy read(String owner) {
    long deadline = System.nanoTime() + budget.toNanos();
    try {
      Path directory = directory(owner, false);
      ApprovalPolicy policy = read(directory.resolve("approval-policy.json"));
      requireTime(deadline);
      return policy;
    } catch (IOException failure) {
      throw new IllegalStateException(
          "Personal approval policy is unavailable; automatic approval is disabled", failure);
    }
  }

  @Override
  public ApprovalPolicy save(String owner, ApprovalPolicy expected) {
    Objects.requireNonNull(expected);
    long deadline = System.nanoTime() + budget.toNanos();
    try {
      Path directory = directory(owner, true);
      try (FileChannel channel =
              FileChannel.open(
                  directory.resolve("approval-policy.lock"),
                  StandardOpenOption.CREATE,
                  StandardOpenOption.WRITE,
                  LinkOption.NOFOLLOW_LINKS);
          FileLock held = lock(channel, deadline)) {
        if (!held.isValid()) throw new IOException("Policy writer lost its lock");
        Path target = directory.resolve("approval-policy.json");
        ApprovalPolicy current = read(target);
        if (current.revision() != expected.revision())
          throw new IllegalStateException("Approval policy changed; reload before saving");
        if (current.revision() == ApprovalPolicy.MAX_REVISION)
          throw new IllegalStateException("Approval policy revision is exhausted");
        ApprovalPolicy saved =
            new ApprovalPolicy(
                current.revision() + 1, expected.mode(), expected.bot(), expected.rules());
        byte[] content = JSON.writeValueAsBytes(saved);
        if (content.length > MAX_BYTES)
          throw new IllegalArgumentException("Approval policy is too large");
        Path staged = Files.createTempFile(directory, ".approval-policy-", ".json");
        try {
          try (FileChannel output =
              FileChannel.open(staged, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer bytes = ByteBuffer.wrap(content);
            while (bytes.hasRemaining()) {
              requireTime(deadline);
              output.write(bytes);
            }
            output.force(true);
          }
          requireTime(deadline);
          Files.move(
              staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
          // Publication may have happened if the deadline expired during rename. Preserve
          // uncertainty
          // rather than reporting success or retrying this revision-changing mutation.
          requireTime(deadline);
          return saved;
        } finally {
          Files.deleteIfExists(staged);
        }
      }
    } catch (IOException failure) {
      throw new IllegalStateException(
          "Approval policy save could not be confirmed; reload before retrying", failure);
    }
  }

  private Path directory(String owner, boolean create) throws IOException {
    ApprovalPolicy.text(owner, "Policy owner", 512);
    Path selected =
        Objects.requireNonNull(directories.apply(owner), "Personal policy directory")
            .toAbsolutePath()
            .normalize();
    Path current = selected.getRoot();
    for (Path component : selected) {
      current = current.resolve(component);
      if (Files.isSymbolicLink(current))
        throw new IOException("Linked policy directory is refused");
    }
    if (create) Files.createDirectories(selected);
    return selected;
  }

  private static ApprovalPolicy read(Path file) throws IOException {
    try (FileChannel input =
        FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
      if (input.size() > MAX_BYTES) throw new IOException("Approval policy exceeds the size limit");
      ByteBuffer bytes = ByteBuffer.allocate(MAX_BYTES + 1);
      while (input.read(bytes) > 0) {
        if (!bytes.hasRemaining()) throw new IOException("Approval policy exceeds the size limit");
      }
      byte[] content = new byte[bytes.position()];
      bytes.flip();
      bytes.get(content);
      ApprovalPolicy result = JSON.readValue(content, ApprovalPolicy.class);
      if (result == null) throw new IOException("Approval policy must be a complete document");
      return result;
    } catch (NoSuchFileException absent) {
      return ApprovalPolicy.manual();
    }
  }

  private static FileLock lock(FileChannel channel, long deadline) throws IOException {
    while (true) {
      requireTime(deadline);
      try {
        FileLock lock = channel.tryLock();
        if (lock != null) return lock;
      } catch (OverlappingFileLockException occupied) {
        // Another participating writer in this process owns it; share the same deadline.
      }
      LockSupport.parkNanos(
          Math.min(Duration.ofMillis(5).toNanos(), Math.max(1, deadline - System.nanoTime())));
    }
  }

  private static void requireTime(long deadline) throws IOException {
    if (Thread.currentThread().isInterrupted() || System.nanoTime() - deadline >= 0)
      throw new IOException("Personal approval policy IO deadline expired");
  }
}
