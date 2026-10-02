package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.server.agents.Watchers;
import io.aeyer.plowshare.server.session.SessionRegistry;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Puts the event channel on a URL, and builds the registry both roles meet in.
 *
 * <h2>No {@code setAllowedOrigins}, and on this socket that is the browser's
 * only protection</h2>
 *
 * <p>Spring's default refuses an upgrade whose {@code Origin} is another site,
 * and nothing here relaxes it. The listener is the one role a browser
 * <em>can</em> hold — it needs no {@code FileAccess} implementation to hold it —
 * so this is the socket a page can plausibly open. What allowing all origins
 * would cost: any page the operator happens to visit could open a listener on
 * this server — which is running on the same machine as the browser showing that
 * page, and is therefore reachable from it by name — and watch a session's jobs
 * go past: what was run, on what, and how it ended. <b>Guessing an id is all
 * that would then stand in the way, and this project's ids are not built to
 * resist that</b>: the registry asks only that an id be non-blank, {@code
 * ChannelClient} takes whatever id it is handed and null-checks it and nothing
 * else, and the file channel's own tests attach under the words {@code closing}
 * and {@code crowd}. The origin check is what makes the guess unreachable rather
 * than merely unlikely.
 *
 * <p><b>Two things now stand behind it, and this paragraph used to say nothing
 * did.</b> It read: "The design spec lists 'the server binds loopback in every
 * deployment this project has' among the bounds on a session id being a
 * capability; that is a fact about how it is run and not one this repository
 * establishes — {@code application.yml} sets a port and no {@code
 * server.address}." <b>Both halves of that are now out of date.</b> That file
 * sets {@code server.address: ${PLOWSHARE_BIND:127.0.0.1}}, and {@code
 * BindAddressTest} fails if the line goes; and slice 4 added {@code AuthFilter},
 * which refuses an upgrade to this path with a 401 unless the request carries
 * the operator's access token — measured, over a real socket, by {@code
 * AuthFilterTest} and by {@code
 * ConversationEndToEndTest.an_unauthenticated_session_attaches_neither_role}.
 *
 * <p><b>None of that makes this absence less load-bearing, and the reason is
 * specific to a browser.</b> The credential a page presents on an upgrade is a
 * cookie, because a page cannot set a header; a cookie is attached by the
 * browser and not by the page, so a <em>foreign</em> page reaching this port is
 * exactly the party whose request might carry the operator's cookie without the
 * operator having asked. What keeps that request out is this check, and the
 * cookie's own {@code SameSite} — which {@code AuthController} now sets to
 * {@code Strict} on both cookies it issues. Two independent barriers, and both
 * of them now exist; this one is still the load-bearing half, because it is the
 * one that does not depend on a browser honouring an attribute.
 *
 * <p>So the absence is the mechanism, and it is written down because a line
 * added here in good faith — to let a development page connect, say — would
 * remove it without anything else changing. {@code
 * EventChannelTest.an_upgrade_from_a_foreign_origin_is_refused} is the
 * instrument, so the absence fails a build rather than a review. When slice 4's
 * browser arrives it is this call that has to be added <em>and argued</em>, with
 * the origin it serves from and not {@code *}.
 *
 * <p>{@link FileChannelConfig} makes the same omission for the other role and
 * says what it costs there, which is a session's files. Two sockets, two
 * absences, two sentences: the one that mattered was not restated by reference,
 * because a reader arriving at this file has a different question.
 *
 * <h2>The message-size bound is not here, and that is deliberate</h2>
 *
 * <p>A reader who arrives holding {@code CloseStatus[code=1009]} on <em>this</em>
 * socket is in the right neighbourhood and the wrong file. {@link
 * FileChannelConfig#webSocketContainer()} is the one {@code
 * ServletServerContainerFactoryBean} in this application, and a
 * container-wide bean is what it is — <b>measured, not assumed</b>: with it
 * present a frame far above the container's 8 192 default crosses this path as
 * well as the file channel's, and with its setter deleted both refuse one with
 * the same close reason. {@code
 * FileChannelTest.the_event_socket_inherits_the_bound_this_file_chose} is the
 * instrument.
 *
 * <p>A second bean of that type here would be a second definition of one
 * container, and a second <em>number</em> would be exactly the pair of
 * disagreeing bounds the file channel's bug was made of. {@code JobEvent}s are
 * small and did not size it; this socket is a passenger on a number chosen for
 * a window of a file.
 *
 * <h2>Still found by component scan — argued here first, measured since</h2>
 *
 * <p>This class carries no {@code @ComponentScan} of its own — it is found
 * because {@code @SpringBootApplication} sits on {@code
 * io.aeyer.plowshare.server}, this class lives in the child package {@code
 * io.aeyer.plowshare.server.ws}, and Spring Boot's component scan is
 * recursive from wherever that annotation sits. Moving this file out of
 * {@code server.api} and into {@code server.ws} changed nothing about that:
 * both are direct children of the scanned root, so this configuration is
 * found on exactly the terms it was found on before the move.
 *
 * <p><b>No test in the fast suite proves that, and none of them can.</b> Every
 * test that touches this configuration — {@code EventChannelTest}, {@code
 * FileChannelTest}, {@code AuthFilterTest} — builds its Spring context with an
 * explicit {@code @Import(EventChannelConfig.class)}. An {@code @Import} finds
 * the class whether or not component scan would have; these tests prove the
 * configuration still <em>functions</em> once wired, and prove nothing about
 * whether scanning alone would have found it. A {@code @Configuration} that
 * quietly fell outside the scanned tree — moved one package too far, say, or
 * annotated in a way that opted it out — would leave all three green. The
 * only tests that build their context the other way, from {@code
 * PlowshareServerApplication} under {@code @SpringBootTest} with no {@code
 * @Import} of this class at all, are {@code EndToEndTest} and {@code
 * ConversationEndToEndTest}; those are the ones a break here would fail, and
 * both need a real database.
 *
 * <p><b>They have since run, and the argument held — but not the part of it
 * this heading claims.</b> Scanning does find this class; what it found was a
 * wiring cycle instead, which no amount of reasoning about package trees was
 * going to predict, and which failed every test in both suites. The lesson is
 * in {@code BootWiringTest}: a scanned context is a measurement nothing else
 * substitutes for, and it does not have to cost a database to take. A reader
 * who moves this package again should still run one of those two suites, and
 * should expect {@code BootWiringTest} to have failed first.
 */
@Configuration
@EnableWebSocket
public class EventChannelConfig implements WebSocketConfigurer {

    /**
     * The routing table for frames arriving on this channel, when one was
     * built.
     *
     * <p><b>The table itself moved to {@link FrameRoutingConfig} and this field
     * is what that move cost.</b> It used to be a {@code @Bean} here holding a
     * literal, empty {@code Map}; the first real handler needs {@code
     * ProjectStore}, and five test contexts import this class to stand up the
     * web layer with no database behind it, so a table assembled here would
     * have made the channel unbuildable in every one of them. That class's own
     * javadoc carries the rest of the argument.
     *
     * <p>An {@link ObjectProvider} and not a plain {@link FrameRouter}, for the
     * same reason {@code FileChannelConfig} holds one: a dependency that is
     * absent in a context this class must still be constructible in cannot be a
     * constructor parameter Spring has to resolve first. {@link
     * #routesNothing()} is what a context without one gets, and {@code
     * EventChannelTest} — which is one of them — is what measures that it is a
     * working channel rather than a broken one.
     *
     * <p><b>Holding a provider is necessary and was never sufficient.</b> It
     * was asked inside {@link #eventChannelHandler(SessionRegistry)}, which
     * resolved the router while that bean was being built and closed a cycle
     * through {@code AgentFrames} and {@code jobStore} that refused every boot
     * of a component-scanned context. The provider is now asked from inside a
     * lambda the handler holds; {@code BootWiringTest} is what fails if that
     * edge becomes eager again.
     */
    private final ObjectProvider<FrameRouter> routers;

    /**
     * Who asked for the tokens.
     *
     * <p>A field rather than a parameter of the bean method, because {@link
     * #registerWebSocketHandlers} calls that method itself and would otherwise
     * have nothing to hand it. Injected rather than created here, which is the
     * whole point: the instance this passes must be the one {@code
     * JobStreamHandler} holds.
     */
    private final Watchers watchers;

    private ObjectProvider<UsageSubscriptions> usageSubscriptions;
    @org.springframework.beans.factory.annotation.Autowired
    public void usageSubscriptions(ObjectProvider<UsageSubscriptions> source) { usageSubscriptions = source; }

    public EventChannelConfig(ObjectProvider<FrameRouter> routers, Watchers watchers) {
        this.routers = Objects.requireNonNull(routers, "routers");
        this.watchers = Objects.requireNonNull(watchers, "watchers");
    }

    /**
     * The one session registry for this server.
     *
     * <p>Defined here because this is the first wiring that needs one, and it
     * belongs in exactly one place: a session is the object in which the file
     * provider and the listener find each other, so a second registry would be
     * two sessions with one name, each holding half of what a job has to ask
     * about. <b>{@link FileChannelConfig} injects this bean rather than building
     * one</b>, and its handler is what fills the file-provider role — so the two
     * roles really do meet in one object, which is the whole of what makes a run
     * submitted under an id reach the machine holding that id's channel. (This
     * paragraph said that configuration "does not attach to it today", which was
     * true for exactly as long as the role had no producer.)
     *
     * <p>A {@code @Bean} rather than a {@code @Component} on the class itself,
     * which is how this repository wires everything that is not a controller,
     * and which keeps {@code server.session} free of Spring: that package is
     * exercised without a container, and a registry that could only be reached
     * through a socket would be an instrument that cannot be pointed at its own
     * edges.
     */
    @Bean
    public SessionRegistry sessionRegistry() {
        return new SessionRegistry();
    }

    /**
     * The listener channel's handler, holding the lookup rather than its answer.
     *
     * <p><b>The lambda is what keeps this application bootable</b>, and it is
     * not a style choice. {@code jobStore} publishes through this bean, so it
     * depends on it; asking {@link #routers} here rather than inside the lambda
     * would make this bean depend on {@code frameRouter}, which depends on
     * {@code AgentFrames}, which depends on the services {@code jobStore} is —
     * a cycle the container can only refuse. {@link EventChannelHandler#routers}
     * carries the argument for why this edge is the right one of the four to
     * cut and what deferring any of the others would have cost.
     *
     * <p>The fallback stays here, on this end of the lambda, because what an
     * assembled-nothing context should answer is this class's argument to make
     * — see {@link #routesNothing()}. The handler asks once, on its first
     * frame, and remembers.
     */
    @Bean
    public EventChannelHandler eventChannelHandler(SessionRegistry sessions) {
        // `watchers` IS THE SAME BEAN `JobStreamHandler` HOLDS, and it has to
        // be. One records a subscription and the other is asked about it; two
        // instances mean `streaming(session)` answers false forever, which is
        // what happened while a constructor was willing to invent one.
        EventChannelHandler channel = new EventChannelHandler(sessions,
                () -> routers.getIfAvailable(EventChannelConfig::routesNothing),
                watchers);
        if (usageSubscriptions != null) usageSubscriptions.ifAvailable(channel::useUsageSubscriptions);
        return channel;
    }

    /**
     * What this channel answers a frame with when no routing table was built —
     * a router claiming no type at all, so every frame is answered with the
     * {@code NOT_FOUND} outcome naming the type nobody registered.
     *
     * <p><b>It is a real answer and not a stub</b>, which is why the fallback is
     * safe to have: a surface that declares nothing saying so is exactly what a
     * client should hear, and this is the same answer the production router
     * gives for a type it does not claim.
     */
    private static FrameRouter routesNothing() {
        return new FrameRouter(Map.of());
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // sessionRegistry(), not a field: a @Configuration class that injected a
        // bean it also defines is a cycle, and the CGLIB proxy makes this call
        // return the same singleton — the same shape, for the same reason, as
        // FileChannelConfig's call to its own handler method.
        registry.addHandler(eventChannelHandler(sessionRegistry()), EventChannelHandler.PATH)
                .addInterceptors(new HandleInterceptor());
    }
}
