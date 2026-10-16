package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.InformationNavigation.Changed;
import io.aeyer.plowshare.protocol.InformationRequest;
import io.aeyer.plowshare.protocol.InformationResponse;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.io.IOException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;

/** Bind the shipped configuration and real services without starting workers or a database. */
class InformationFeatureConfigTest {
  private final InformationNavigationRepository repository =
      mock(InformationNavigationRepository.class);
  private final ProjectMembers members = mock(ProjectMembers.class);
  private final InformationRequest.Selection selection = InformationRequest.Selection.personal();

  private ApplicationContextRunner runner() throws IOException {
    var shipped =
        new YamlPropertySourceLoader().load("shipped", new ClassPathResource("application.yml"));
    return new ApplicationContextRunner()
        .withInitializer(
            context -> shipped.forEach(context.getEnvironment().getPropertySources()::addLast))
        .withUserConfiguration(InformationFeatureConfig.class)
        .withBean(InformationNavigationRepository.class, () -> repository)
        .withBean(InformationAccess.class, () -> new InformationAccess(members))
        .withBean(UnitOfWork.class, () -> UnitOfWork.NONE);
  }

  @Test
  void replacing_packaged_configuration_still_defaults_optional_controls_on() throws IOException {
    runner()
        .withInitializer(
            context ->
                assertNotNull(context.getEnvironment().getPropertySources().remove("shipped")))
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              assertTrue(capabilities(context.getBean(InformationNavigationService.class)));
              assertDoesNotThrow(context.getBean(InformationContentSettings.class)::requireWrites);
            });
  }

  @Test
  void shipped_defaults_enable_writes_and_keep_scope_authority() throws IOException {
    runner()
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              var navigation = context.getBean(InformationNavigationService.class);
              assertTrue(capabilities(navigation));
              assertDoesNotThrow(context.getBean(InformationContentSettings.class)::requireWrites);
              UUID id = UUID.randomUUID();
              when(repository.delete("reader", id, 1)).thenReturn(new Changed(id, 2, false));
              navigation.execute(
                  "reader",
                  new InformationRequest.NavigationViewDelete(
                      selection, UUID.randomUUID(), id, 1L));
              verify(repository).delete("reader", id, 1);

              // Enabled availability cannot give a service identity a Personal scope.
              assertThrows(
                  CallerFault.class,
                  () ->
                      navigation.execute(
                          "@service/fixture",
                          new InformationRequest.NavigationCapabilities(selection)));
              var denied =
                  new InformationRequest.Selection(
                      new InformationRequest.Scope("project", "ungranted", false),
                      "documents",
                      null);
              assertThrows(
                  CallerFault.class,
                  () ->
                      navigation.execute(
                          "reader", new InformationRequest.NavigationCapabilities(denied)));
            });
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "PLOWSHARE_INFORMATION_NAVIGATION_WRITES_ENABLED=false",
        "plowshare.information.navigation-writes-enabled=false"
      })
  void navigation_override_suspends_only_navigation_writes(String setting) throws IOException {
    runner()
        .withPropertyValues(setting)
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              var navigation = context.getBean(InformationNavigationService.class);
              assertFalse(capabilities(navigation));
              assertDoesNotThrow(context.getBean(InformationContentSettings.class)::requireWrites);
              CallerFault refused =
                  assertThrows(
                      CallerFault.class,
                      () ->
                          navigation.execute(
                              "reader",
                              new InformationRequest.NavigationViewDelete(
                                  selection, UUID.randomUUID(), UUID.randomUUID(), 1L)));
              assertEquals("document-navigation-writes-disabled", refused.getMessage());
              verifyNoInteractions(repository);
            });
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "PLOWSHARE_INFORMATION_CONTENT_TYPES_ENABLED=false",
        "plowshare.information.content-types-enabled=false"
      })
  void authored_content_override_leaves_navigation_enabled(String setting) throws IOException {
    runner()
        .withPropertyValues(setting)
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              assertTrue(capabilities(context.getBean(InformationNavigationService.class)));
              assertThrows(
                  CallerFault.class,
                  context.getBean(InformationContentSettings.class)::requireWrites);
            });
  }

  @Test
  void explicit_properties_take_precedence_over_environment_placeholder_values()
      throws IOException {
    runner()
        .withPropertyValues(
            "PLOWSHARE_INFORMATION_NAVIGATION_WRITES_ENABLED=false",
            "PLOWSHARE_INFORMATION_CONTENT_TYPES_ENABLED=false",
            "plowshare.information.navigation-writes-enabled=true",
            "plowshare.information.content-types-enabled=true")
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              assertTrue(capabilities(context.getBean(InformationNavigationService.class)));
              assertDoesNotThrow(context.getBean(InformationContentSettings.class)::requireWrites);
            });
  }

  @ParameterizedTest
  @ValueSource(strings = {"CONTENT_TYPES", "NAVIGATION_WRITES"})
  void malformed_switch_value_refuses_startup(String name) throws IOException {
    runner()
        .withPropertyValues("PLOWSHARE_INFORMATION_" + name + "_ENABLED=definitely")
        .run(context -> assertNotNull(context.getStartupFailure()));
  }

  private boolean capabilities(InformationNavigationService navigation) {
    return assertInstanceOf(
            InformationResponse.NavigationCapabilities.class,
            navigation.execute("reader", new InformationRequest.NavigationCapabilities(selection)))
        .value()
        .writesEnabled();
  }
}
