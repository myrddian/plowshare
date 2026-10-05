package io.aeyer.plowshare.server.embedding;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor;
import org.springframework.jdbc.core.JdbcTemplate;

/** Boundary validation runs without PostgreSQL or model providers. */
class EmbeddingSpaceTest {
  @Test
  void rejectsInvalidDescriptorsBeforePersistence() {
    for (int width : new int[] {0, -1, 16001}) {
      assertThrows(IllegalArgumentException.class, () -> definition("model", width, "query: "));
    }
    for (String name : new String[] {"", " ", " model", "model\n", "x".repeat(513), "model\0"}) {
      assertThrows(IllegalArgumentException.class, () -> definition(name, 3, "query: "));
    }
    assertThrows(IllegalArgumentException.class, () -> definition("model", 3, "x".repeat(16385)));
    assertThrows(IllegalArgumentException.class, () -> definition("model", 3, "query\0"));
    assertThrows(IllegalArgumentException.class, () -> definition("model", 3, null));
  }

  @Test
  void prefixWhitespaceAndFullStorageWidthArePreserved() {
    var definition = definition("model", 16000, "query: \n");
    assertEquals("query: \n", definition.queryPrefix());
    assertEquals(16000, definition.dimensions());
    assertEquals(EmbeddingSpace.Normalization.L2, EmbeddingSpace.Normalization.fromStored("l2"));
    assertThrows(
        IllegalArgumentException.class, () -> EmbeddingSpace.Normalization.fromStored("guess"));
  }

  @Test
  void malformedIdentityNeverReachesTheDatabase() {
    var jdbc = mock(JdbcTemplate.class);
    EmbeddingSpaceRepository repository = new JdbcEmbeddingSpaceRepository(jdbc);
    for (String id : new String[] {"", "0".repeat(63), "G".repeat(64), "0".repeat(65)}) {
      assertThrows(IllegalArgumentException.class, () -> repository.find(id));
    }
    assertThrows(IllegalArgumentException.class, () -> repository.find(null));
    assertThrows(NullPointerException.class, () -> repository.register(null));
    verifyNoInteractions(jdbc);
  }

  @Test
  void repositorySupportsSpringExceptionTranslationAndTypedInjection() {
    var jdbc = mock(JdbcTemplate.class);
    try (var context = new AnnotationConfigApplicationContext()) {
      context.registerBean(JdbcTemplate.class, () -> jdbc);
      context.registerBean(
          PersistenceExceptionTranslationPostProcessor.class,
          () -> {
            var translation = new PersistenceExceptionTranslationPostProcessor();
            translation.setProxyTargetClass(true);
            return translation;
          });
      context.register(JdbcEmbeddingSpaceRepository.class);
      context.refresh();
      // Spring may initialize JdbcTemplate; only calls caused by the tested
      // operation belong to the no-database-access assertion below.
      clearInvocations(jdbc);
      var repository = context.getBean(EmbeddingSpaceRepository.class);
      assertTrue(AopUtils.isAopProxy(repository));
      assertThrows(IllegalArgumentException.class, () -> repository.find("invalid"));
      verifyNoInteractions(jdbc);
    }
  }

  private static EmbeddingSpace.Definition definition(String model, int width, String prefix) {
    return new EmbeddingSpace.Definition(
        model, "revision", width, prefix, "", "mean", EmbeddingSpace.Normalization.NONE, "none");
  }
}
