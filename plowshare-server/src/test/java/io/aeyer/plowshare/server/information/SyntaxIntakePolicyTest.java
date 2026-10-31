package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.DocumentType;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class SyntaxIntakePolicyTest {
  @Test
  void syntaxIntakeCannotDefaultToAutomaticModelWork() {
    assertEquals("explicit_only", write(true).processingPolicy());
    assertEquals(
        "automatic",
        write(false).processingPolicy(),
        "Ordinary code uploads retain their own requested processing contract");
  }

  private static InformationCatalogueRepository.AdmissionWrite write(boolean syntaxOnly) {
    return new InformationCatalogueRepository.AdmissionWrite(
        "A.java",
        "class A {}".getBytes(StandardCharsets.UTF_8),
        "text/plain",
        "workspace-code:fixture",
        "source",
        List.of(),
        "session",
        1000,
        0,
        syntaxOnly,
        new DocumentType("code", "java"),
        "a".repeat(64));
  }
}
