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
  @Test
  void specialistRepositoriesPermitBootClassProxiesWithoutChangingInterfaceInjection() {
    // Boot enables class-based exception translation by default. These specialist repositories
    // still expose their typed interfaces; a final implementation prevents application startup
    // before JDBC, a listener or any domain operation can run.
    for (Class<?> repository :
        java.util.List.of(
            io.aeyer.plowshare.server.access.JdbcIdentityRepository.class,
            io.aeyer.plowshare.server.access.JdbcScopedGrantRepository.class,
            io.aeyer.plowshare.server.access.JdbcOperationRecords.class,
            io.aeyer.plowshare.server.access.JdbcSystemOperationRecords.class,
            io.aeyer.plowshare.server.allowances.JdbcAllowanceRepository.class,
            io.aeyer.plowshare.server.archive.JdbcPromotionSourceRepository.class,
            io.aeyer.plowshare.server.embedding.JdbcQueryEmbeddingRepository.class,
            io.aeyer.plowshare.server.llm.accounting.JdbcInferenceAdmissions.class)) {
      var factory = new org.springframework.aop.framework.ProxyFactory();
      factory.setTargetClass(repository);
      factory.setProxyTargetClass(true);
      factory.addAdvice(
          (org.aopalliance.intercept.MethodInterceptor)
              invocation -> {
                throw new AssertionError("Proxy construction must not invoke persistence");
              });
      var proxy = factory.getProxy();
      assertTrue(AopUtils.isCglibProxy(proxy), repository.getName());
      assertTrue(repository.isInstance(proxy), repository.getName());
    }
  }

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

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void readCapacityCompositionPreservesAccountAndReportRepositoryProxyContracts(
      boolean classProxy) {
    var jdbc = new JdbcTemplate(mock(javax.sql.DataSource.class));
    var caches = mock(io.aeyer.plowshare.server.cache.ProjectionCaches.class);
    when(caches.create(anyString(), anySet()))
        .thenAnswer(call -> io.aeyer.plowshare.server.cache.ProjectionCache.direct());
    try (var context = new AnnotationConfigApplicationContext()) {
      context.registerBean(JdbcTemplate.class, () -> jdbc);
      context.registerBean(
          com.fasterxml.jackson.databind.ObjectMapper.class,
          () -> new com.fasterxml.jackson.databind.ObjectMapper());
      context.registerBean(
          org.springframework.transaction.PlatformTransactionManager.class,
          () -> mock(org.springframework.transaction.PlatformTransactionManager.class));
      context.registerBean(io.aeyer.plowshare.server.cache.ProjectionCaches.class, () -> caches);
      context.registerBean(
          PersistenceExceptionTranslationPostProcessor.class,
          () -> {
            var translation = new PersistenceExceptionTranslationPostProcessor();
            translation.setProxyTargetClass(classProxy);
            return translation;
          });
      context.register(
          io.aeyer.plowshare.server.auth.AdminStore.class,
          io.aeyer.plowshare.server.auth.StoredSessionAuthority.class,
          io.aeyer.plowshare.server.llm.accounting.JdbcUsageReportRepository.class,
          io.aeyer.plowshare.server.reads.ReportReadConfiguration.class,
          io.aeyer.plowshare.server.reads.ReadCapacityConfiguration.class,
          io.aeyer.plowshare.server.ws.SocketAuthorization.class);
      context.refresh();
      assertNotNull(
          context.getBean(io.aeyer.plowshare.server.auth.AdminStore.class),
          "Legacy account consumers require their existing concrete bean");
      assertNotNull(context.getBean(io.aeyer.plowshare.server.ws.SocketAuthorization.class));
      assertInstanceOf(
          io.aeyer.plowshare.server.llm.accounting.CachedUsageReportRepository.class,
          context.getBean(io.aeyer.plowshare.server.llm.accounting.UsageReportRepository.class));
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
