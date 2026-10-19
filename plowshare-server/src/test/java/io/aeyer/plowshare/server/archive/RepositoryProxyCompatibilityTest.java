package io.aeyer.plowshare.server.archive;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.applications.ApplicationIdentityRepository;
import io.aeyer.plowshare.server.applications.JdbcApplicationIdentityRepository;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor;
import org.springframework.jdbc.core.JdbcTemplate;

/** Reproduces the Boot exception-translation proxy mode without needing PostgreSQL. */
class RepositoryProxyCompatibilityTest {
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void archiveCompositionAcceptsRepositoryInterfaceAndClassProxies(boolean classProxy) {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.registerBean(JdbcTemplate.class, () -> jdbc);
      context.registerBean(
          PersistenceExceptionTranslationPostProcessor.class,
          () -> {
            PersistenceExceptionTranslationPostProcessor translation =
                new PersistenceExceptionTranslationPostProcessor();
            translation.setProxyTargetClass(classProxy);
            return translation;
          });
      context.register(MemoryStore.class);
      context.registerBean(
          Archive.class,
          () ->
              new ArchiveConfig()
                  .archive(
                      context.getBean(MemoryRepository.class),
                      mock(ReasonLog.class),
                      mock(io.aeyer.plowshare.server.llm.EmbeddingClient.class),
                      mock(UnitOfWork.class),
                      new ArchiveProperties(),
                      mock(MemoryQueries.class),
                      new org.springframework.beans.factory.support.StaticListableBeanFactory(
                              java.util.Map.of("writes", mock(MemoryWrites.class)))
                          .getBeanProvider(MemoryWrites.class)));
      context.refresh();
      MemoryRepository memories = context.getBean(MemoryRepository.class);
      assertEquals(classProxy, AopUtils.isCglibProxy(memories));
      assertEquals(!classProxy, AopUtils.isJdkDynamicProxy(memories));
      assertSame(memories, context.getBean(MemoryReadRepository.class));
      assertSame(memories, context.getBean(MemoryWriteRepository.class));
      Archive archive = context.getBean(Archive.class);
      new io.aeyer.plowshare.server.embedding.EmbeddingConfig()
          .dualEmbeddingBinding(
              mock(io.aeyer.plowshare.server.embedding.DualEmbeddings.class),
              archive,
              memories,
              mock(io.aeyer.plowshare.server.documents.DocumentStore.class),
              mock(io.aeyer.plowshare.server.documents.RetrievalService.class),
              mock(io.aeyer.plowshare.server.documents.IngestService.class),
              mock(io.aeyer.plowshare.server.documents.SummaryEmbeddings.class),
              mock(PassageIndex.class),
              mock(io.aeyer.plowshare.server.agents.digests.Digester.class))
          .afterSingletonsInstantiated();
      assertNotNull(archive);
      verify(jdbc).afterPropertiesSet();
      verifyNoMoreInteractions(jdbc);
    }
  }

  @Test
  void applicationAndContextRepositoriesSupportClassProxiesAndTypedInjection() {
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
      context.register(
          JdbcApplicationRegistrations.class,
          JdbcConversationContextRepository.class,
          JdbcApplicationIdentityRepository.class);
      context.refresh();
      var registrations = context.getBean(ApplicationRegistrations.class);
      var conversations = context.getBean(ConversationContextRepository.class);
      var identities = context.getBean(ApplicationIdentityRepository.class);
      assertTrue(AopUtils.isCglibProxy(registrations));
      assertTrue(AopUtils.isCglibProxy(conversations));
      assertTrue(AopUtils.isCglibProxy(identities));
      assertFalse(registrations.required("fixture-project"));
      assertTrue(conversations.ownership("fixture-conversation").isEmpty());
      assertTrue(identities.find("fixture-project", false).isEmpty());
    }
  }
}
