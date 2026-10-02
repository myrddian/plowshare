package io.aeyer.plowshare.server;

import io.aeyer.plowshare.server.agents.JobsProperties;
import io.aeyer.plowshare.server.api.ConversationsProperties;
import io.aeyer.plowshare.server.auth.AuthConfig;
import io.aeyer.plowshare.server.llm.LlmProperties;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * The server: Postgres, embeddings and every archive semantic. It is the only
 * half of Plowshare that talks to an LLM or holds durable state.
 *
 * <p>Present from the first commit because the Spring Boot plugin resolves
 * {@code bootJar}'s main class off the classpath — without this, {@code
 * ./gradlew build} fails on a module that otherwise compiles and tests clean.
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
        LlmProperties.class, ConversationsProperties.class, JobsProperties.class})
public class PlowshareServerApplication {

    /**
     * The property that turns the console announcement on, and it is set
     * <em>here</em> rather than in {@code application.yml} on purpose.
     *
     * <p>{@link AuthConfig}'s {@code ApplicationReadyEvent} listener mints a
     * bootstrap token, writes an operator token to this path at mode 600 and
     * prints the console URL. That path is a real file on the machine running
     * the build, holding the live credential of whatever server its operator is
     * using — so a value in the YAML, which every test reads too, would have each
     * full-application test context overwrite it and make a running server
     * unreachable with nothing anywhere saying so.
     *
     * <p>No test context calls this method: a {@code @SpringBootTest} bootstraps
     * from the annotated class, and the {@code SpringApplicationBuilder} and
     * {@code @ImportAutoConfiguration} boots elsewhere in the suite name their own
     * configuration class. Leaving the value here is what makes "announce in
     * production, not under test" a fact about the wiring rather than a rule each
     * test has to remember. {@code AuthProperties.getTokenFile} carries the
     * argument at length.
     *
     * <p>Both halves were measured. A real {@code bootRun} through this method
     * printed the console line and wrote the file; a whole {@code ./gradlew
     * check} left {@code ~/.config/plowshare/} holding exactly what it held
     * before, which was {@code lm-key} and nothing else.
     */
    static final String TOKEN_FILE_PROPERTY = "plowshare.auth.token-file";

    /**
     * Where this server keeps what it owns on disk, and it is set <em>here</em>
     * for {@link #TOKEN_FILE_PROPERTY}'s reason exactly.
     *
     * <p>{@code DataConfig} creates the tree and writes a layout marker into it
     * at context refresh. That is real directories on the machine running the
     * build, and this repository stands dozens of Spring contexts up against a
     * real database — so a value in {@code application.yml}, which every one of
     * those contexts reads, would have each of them create and mark a {@code
     * data/} beside whichever module it ran in. Left here, a test context has no
     * data directory, {@code DataLayout.NONE} is what it gets, and nothing is
     * made.
     *
     * <p><b>It cannot be written into {@code application.yml} even as a
     * comment-with-a-value.</b> A config file outranks {@code
     * setDefaultProperties}, so a key there would be the value every real server
     * ran with and this line would be dead. {@code DataProperties.getDir} says
     * so on the accessor.
     *
     * <p>{@code data}, relative, resolved against the process's working
     * directory — {@code plowshare.llm.sampling-directory}'s shape and its
     * reason: there is no absolute path that is right on two machines. {@code
     * PLOWSHARE_DATA_DIR} moves it, which is why the key is spelled {@code dir}
     * rather than {@code directory}; Spring's relaxed binding maps that variable
     * onto this name and onto no other.
     */
    static final String DATA_DIR_PROPERTY = "plowshare.data.dir";

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(PlowshareServerApplication.class);
        // DEFAULT properties, which is Spring's lowest-precedence source: an
        // operator who passes --plowshare.auth.token-file=... or exports
        // PLOWSHARE_AUTH_TOKEN_FILE, PLOWSHARE_DATA_DIR or
        // --plowshare.data.dir=... still wins over these.
        application.setDefaultProperties(Map.of(
                TOKEN_FILE_PROPERTY, AuthConfig.defaultTokenFile().toString(),
                DATA_DIR_PROPERTY, "data"));
        application.run(args);
    }
}
