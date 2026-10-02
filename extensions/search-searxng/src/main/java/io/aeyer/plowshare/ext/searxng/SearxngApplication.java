package io.aeyer.plowshare.ext.searxng;

import okhttp3.OkHttpClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * The SearXNG provider: a standalone process serving {@code
 * plowshare-protocol}'s search contract over one operator-run SearXNG
 * instance, on port 8086.
 *
 * <p>This is its own process rather than a bean inside {@code
 * plowshare-server} because that is the whole shape this slice builds — see
 * {@code settings.gradle.kts}'s comment on why an extensions module exists
 * and why it is deliberately absent from the server's classpath. {@code
 * RemoteSearchProvider} dials whatever {@code baseUrl} an operator registers
 * this process under; nothing on the Plowshare side needs to compile against
 * a line of code in this module.
 */
@SpringBootApplication
@EnableConfigurationProperties(SearxngProperties.class)
public class SearxngApplication {

    public static void main(String[] args) {
        SpringApplication.run(SearxngApplication.class, args);
    }

    /**
     * Shared across every call this process makes, on {@code
     * RemoteSearchProvider}'s own reasoning: a client built once keeps one
     * connection pool, and {@link SearxngClient} applies its per-call budget
     * through {@link okhttp3.Call#timeout()} rather than by rebuilding a
     * client per request.
     */
    @Bean
    OkHttpClient okHttpClient() {
        return new OkHttpClient();
    }
}
