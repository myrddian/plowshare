package io.aeyer.plowshare.server;

import io.aeyer.plowshare.server.agents.JobsProperties;
import io.aeyer.plowshare.server.api.ConversationsProperties;
import io.aeyer.plowshare.server.llm.LlmProperties;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * The server: Postgres, embeddings and every archive semantic. It is the only half of Plowshare
 * that talks to an LLM or holds durable state.
 *
 * <p>Present from the first commit because the Spring Boot plugin resolves {@code bootJar}'s main
 * class off the classpath — without this, {@code ./gradlew build} fails on a module that otherwise
 * compiles and tests clean.
 */
@SpringBootApplication
// Both are plain @ConfigurationProperties classes with no stereotype, so
// component scanning alone would not find either and the bean that needs it
// would be handed one that does not exist. Named here rather than switched on
// with @ConfigurationPropertiesScan: this is the list of the properties classes
// no other configuration owns, and listing them says which. The ones that belong
// to a subsystem stay on that subsystem's @Configuration -- AgentsProperties and
// WorkspaceProperties on AgentsConfig, ArchiveProperties on ArchiveConfig,
// AuthProperties on AuthConfig -- because a class that is only ever read there
// is wired where it is read. ConversationsProperties has no such configuration:
// the api package holds controllers, and the reader is ConversationController.
// (The two WebSocket configs it once held live in ws/ now.)
@EnableConfigurationProperties({
  LlmProperties.class,
  ConversationsProperties.class,
  JobsProperties.class
})
public class PlowshareServerApplication {

  /**
   * Starts the configured server without choosing deployment origins, credentials or directories.
   */
  public static void main(String[] args) {
    SpringApplication application = new SpringApplication(PlowshareServerApplication.class);
    // Setup mode is a lifecycle default. Deployment paths and addresses are always explicit.
    application.setDefaultProperties(Map.of("plowshare.auth.first-run-setup", true));
    application.run(args);
  }
}
