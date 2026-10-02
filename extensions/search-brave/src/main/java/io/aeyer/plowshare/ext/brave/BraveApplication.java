package io.aeyer.plowshare.ext.brave;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * The Brave provider: a standalone process serving {@code
 * plowshare-protocol}'s search contract over Brave's public Web Search API,
 * on port 8084.
 *
 * <h2>Where the vendor credential actually lives</h2>
 *
 * <p>{@code SEARCH_BRAVE_API_KEY} is read here, directly from the process
 * environment, and handed to {@link BraveClient} as a plain constructor
 * argument — never through {@link BraveProperties}, and never through this
 * module's {@code application.properties}. That routing is the entire point
 * of the out-of-process design (spec §3): the credential that pays Brave's
 * bill exists only inside this process's environment and this process's
 * memory, and Plowshare — the process holding the token ledger, {@code
 * FileAccess} and the archive — never sees it, never configures it, and could
 * not leak it even by a bug, because there is no field anywhere on that side
 * for it to be assigned to. A crash or a compromise of this process costs the
 * operator this one key; it costs nothing else this repository protects.
 *
 * <p>This is its own process rather than a bean inside {@code
 * plowshare-server} for the reason {@code SearxngApplication} gives, applying
 * here with the vendor credential as the sharper case for it.
 */
@SpringBootApplication
@EnableConfigurationProperties(BraveProperties.class)
public class BraveApplication {

    /**
     * Read once, at startup, rather than by {@link BraveClient} on every
     * call — the environment does not change between requests, and reading
     * it here is what keeps the credential out of {@link BraveProperties}
     * (see that class's javadoc) rather than merely a matter of style.
     */
    static final String API_KEY_ENV_VAR = "SEARCH_BRAVE_API_KEY";

    public static void main(String[] args) {
        SpringApplication.run(BraveApplication.class, args);
    }

    @Bean
    OkHttpClient okHttpClient() {
        return new OkHttpClient();
    }

    @Bean
    BraveClient braveClient(OkHttpClient http, ObjectMapper json, BraveProperties properties) {
        return new BraveClient(http, json, properties, System.getenv(API_KEY_ENV_VAR));
    }
}
