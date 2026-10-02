package io.aeyer.plowshare.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * The server binds loopback unless an operator says otherwise, and something
 * fails if it stops.
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>The design spec offered "the server binds loopback in every deployment this
 * project has" as one of three bounds on a session id that was, in its own
 * words, a bearer capability with no authentication behind it. <b>Slice 4 built
 * the authentication</b> — {@code AuthFilter} gates every {@code /v1} path and
 * both sockets — so this key is no longer the only thing in front of a session.
 * It is not thereby redundant: {@code plowshare.auth.enabled} is one line from
 * being false, and two independent bounds is the arrangement this repository
 * wants rather than one behind the other. The sentence quoted above was false
 * when it was written: {@code application.yml} set {@code server.port} and no
 * {@code server.address}, so the container bound every interface. The spec was
 * corrected to say so; the tree has now been changed to make the original claim
 * true, with {@code server.address: ${PLOWSHARE_BIND:127.0.0.1}}.
 *
 * <p><b>Measured: making that change broke no test.</b> 1464 tests, 0 failures,
 * before and after. Every integration test in this repository reaches the server
 * over {@code localhost}, which is the interface it now binds, and {@code
 * InvariantsTest.no_source_names_the_reference_box} is what stops any test using
 * another address. So the suite is <em>indifferent</em> to this setting: delete
 * the line and everything stays green, which makes it a security property
 * nothing protects. This class is the something.
 *
 * <h2>Why the resolved property and not the file</h2>
 *
 * <p>{@code TimeoutConfiguredTest} settled the shape one directory over: what is
 * asserted is what the platform <b>resolved</b>, not the bytes of a file, because
 * reading the file back proves a file exists somewhere a classloader can see and
 * not that anything read it. A context is booted with {@link
 * WebApplicationType#NONE} and no autoconfiguration — Spring Boot's config-data
 * processing loads {@code application.yml} either way, and there is no Tomcat,
 * no datasource and no model in it, so it costs about a second.
 *
 * <h2>What this does not cover, said rather than implied</h2>
 *
 * <p>That Tomcat honours {@code server.address} is Spring Boot's contract and
 * not this repository's to re-test, so this asserts the value the container is
 * handed rather than the socket it ends up bound to. Proving the latter needs a
 * non-loopback address of this host, which not every machine running this suite
 * has — and a test that quietly passed on the machines without one would be
 * worse than this, because its green would mean two different things.
 */
class BindAddressTest {

    private static final String KEY = "server.address";

    /** The environment variable that opens it, which is the whole of the escape
     *  hatch and is therefore asserted rather than assumed. */
    private static final String OVERRIDE = "PLOWSHARE_BIND";

    @Test
    void the_server_binds_loopback_unless_an_operator_says_otherwise()
            throws UnknownHostException {
        String address = resolve();

        assertNotNull(address,
                "Spring resolved no " + KEY + ", so this server binds every interface it can"
                        + " reach. A session id is a name and not a secret, so who can reach"
                        + " the port is one of the two bounds on attaching to one — the other"
                        + " being plowshare.auth.enabled, which an operator can also turn off."
                        + " This key belongs in"
                        + " plowshare-server/src/main/resources/application.yml.");
        assertTrue(InetAddress.getByName(address).isLoopbackAddress(),
                KEY + " resolved to '" + address + "', which is not a loopback address, so a run"
                        + " of this server is reachable from whatever network the box is on."
                        + " That is a deployment an operator may choose deliberately with "
                        + OVERRIDE + "; it is not one to arrive at by editing the default.");
    }

    /**
     * The default is a default and not a wall.
     *
     * <p>The accepted side of the rule, written independently of it: this passes
     * a value that is <em>not</em> the default and asserts it wins. Without it,
     * {@code address: 127.0.0.1} written as a bare literal — no placeholder, no
     * way out — would satisfy the test above and leave an operator who needs
     * network access editing a file inside a jar. The pair is what pins the
     * shape.
     */
    @Test
    void an_operator_opens_it_in_one_place_and_the_placeholder_is_that_place() {
        assertEquals("0.0.0.0", resolve(OVERRIDE + "=0.0.0.0"),
                OVERRIDE + " did not reach " + KEY + ", so the default above is the only value"
                        + " this server can have and the escape the design spec names does not"
                        + " exist. 0.0.0.0 is the value that restores the pre-slice behaviour"
                        + " exactly, which is why it is the one asked for here.");
    }

    /** What Spring Boot resolves {@code server.address} to, having read the real
     *  {@code application.yml}, with {@code properties} laid over it. */
    private static String resolve(String... properties) {
        try (ConfigurableApplicationContext context =
                new SpringApplicationBuilder(Nothing.class)
                        .web(WebApplicationType.NONE)
                        .properties(properties)
                        .run()) {
            Environment environment = context.getEnvironment();
            return environment.getProperty(KEY);
        }
    }

    /** No beans. The subject is what the environment resolved, and every bean
     *  this server has would be a second thing that could fail this test. */
    @Configuration
    static class Nothing {
    }
}
