package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;

import io.aeyer.plowshare.server.agents.Compaction;
import io.aeyer.plowshare.server.agents.JobEvents;
import io.aeyer.plowshare.server.agents.JobStore;
import io.aeyer.plowshare.server.agents.Watchers;
import io.aeyer.plowshare.server.agents.learner.Learner;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.beans.TypeConverter;
import org.springframework.beans.factory.BeanCurrentlyInCreationException;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The scanned wiring has no cycle in it — measured without a database, so it
 * measures on every run and not only on a machine with Docker.
 *
 * <h2>The gap this closes</h2>
 *
 * <p>Every fast test in this repository wires what it needs with {@code
 * @Import} or with a constructor call, which is why they are fast and why not
 * one of them can see a cycle: a cycle is not a property of any bean, it is a
 * property of <em>all</em> of them together, and it only exists once the
 * container is asked to build the real set. The only suites that ask are the
 * two end-to-end ones, and they need Testcontainers — so for the length of a
 * branch built while Docker was down, the whole frame surface was written
 * against a server that could not start. {@code eventChannelHandler} asked for
 * a {@link FrameRouter} while it was being built; the router is assembled from
 * every {@link FrameArea}; {@code AgentFrames} takes the agent services; and
 * one of those is {@code JobStore}, which publishes a job's lifecycle through
 * the very handler that started the chain. Nothing failed until a real context
 * was built, and nothing built one.
 *
 * <h2>How this builds a real context with no database behind it</h2>
 *
 * <p>The two packages are <b>scanned</b>, not imported: {@code
 * AnnotationConfigApplicationContext#scan} over {@code ws} and {@code agents}
 * reaches the same {@code @Configuration} and {@code @Component} classes {@code
 * @SpringBootApplication}'s own scan reaches, and the bean definitions are the
 * production ones rather than a test's copy of them. Two substitutions make it
 * runnable without Postgres, and both are narrow on purpose:
 *
 * <ul>
 *   <li><b>A dependency no scanned class defines becomes a mock</b> — {@code
 *       JdbcTemplate}, the archive's stores, the properties of packages that
 *       are not scanned here. The resolution itself is Spring's: {@link
 *       DefaultListableBeanFactory#resolveDependency} is called first and only
 *       its {@code NoSuchBeanDefinitionException} is answered with a mock, so
 *       an {@code ObjectProvider} parameter still resolves to a provider and a
 *       {@code List<FrameArea>} still collects every area. <b>This is what
 *       keeps the test honest about deferral</b>: the difference between an
 *       eager edge and a deferred one is exactly what Spring decides here, and
 *       nothing in this file second-guesses it.
 *   <li><b>A bean whose factory method throws becomes a mock too</b> — because
 *       a store handed a mocked {@code JdbcTemplate} may well fail while
 *       constructing itself, and what that proves is only that a mock is not a
 *       database. <b>A failure whose cause is a {@link
 *       BeanCurrentlyInCreationException} is rethrown</b>, which is the one
 *       outcome this test exists to catch. Building each bean for real is not
 *       optional here: the edge that closed the loop was a {@code
 *       getIfAvailable} in a {@code @Bean} method's <em>body</em>, invisible to
 *       any reading of signatures, and it is reached only by running the body.
 * </ul>
 *
 * <p>What this deliberately does not assert is that every bean is buildable —
 * {@code EndToEndTest} does that, against a real database, and it is the right
 * place for it. This asserts the one thing that is cheap here and unavailable
 * there.
 *
 * <h2>The second substitution is also this test's own way of going hollow</h2>
 *
 * <p>Mocking a bean whose factory threw is what makes the context buildable, and
 * it is also the one edit that could leave both tests below passing over a graph
 * with no loop left in it: a mock has no dependencies, so a mocked {@code
 * AgentFrames} takes the agent services — and with them the edge back into this
 * package — straight out of what the container measures. A constructor that
 * began throwing for an unrelated reason would do it silently, and from the
 * outside would look exactly like a cycle that had been fixed.
 *
 * <p>{@link #assertReal} is the guard. The three beans the loop is made of are
 * asserted not to be stand-ins, so hollowing this test out costs a failure
 * rather than a green run.
 */
class BootWiringTest {

    /** The two packages the frame surface and its services live in. */
    private static final String[] SCANNED = {
            "io.aeyer.plowshare.server.ws",
            "io.aeyer.plowshare.server.agents"};

    /**
     * A container builds the whole scanned wiring without a circular reference.
     *
     * <p>The assertions after the refresh name the loop's own members, so a
     * future change that drops {@code AgentFrames} or moves {@code jobStore}
     * out of the scan fails here rather than quietly leaving this test with
     * nothing to prove.
     */
    @Test
    void the_scanned_wiring_has_no_cycle_in_it() {
        try (AnnotationConfigApplicationContext context = boot()) {
            assertNotNull(context.getBean(FrameRouter.class),
                    "the routing table is assembled from the scanned areas");
            assertTrue(context.getBeansOfType(FrameArea.class).containsKey("agentFrames"),
                    "and AgentFrames is in it, which is the area that reaches the agent"
                            + " services: " + context.getBeansOfType(FrameArea.class).keySet());
            assertInstanceOf(JobEvents.class, context.getBean(EventChannelHandler.class),
                    "the handler is what a job publishes through, which is the edge back"
                            + " into this package that makes the loop a loop");
            assertNotNull(context.getBean(JobStore.class),
                    "and the store that does the publishing is in the scan");

            // The three assertions above name the loop's members; these three
            // say the container really built them. Each is a place the graph
            // being measured could lose the loop without any of this file's
            // other assertions noticing.
            assertReal(context.getBeansOfType(FrameArea.class).get("agentFrames"), "AgentFrames",
                    "it is the area that reaches the agent services, so a stand-in for it has"
                            + " no edge into JobStore and the loop stops being a loop");
            assertReal(context.getBean(EventChannelHandler.class), "EventChannelHandler",
                    "it is where the loop starts and closes: a stand-in asks the container"
                            + " for no router and publishes no job");
            assertReal(context.getBean(JobStore.class), "JobStore",
                    "it is what does the publishing, and a stand-in has no dependencies at"
                            + " all -- the edge back into this package would simply be gone");
        }
    }

    /**
     * The deferred lookup finds the real table, not the empty fallback.
     *
     * <p><b>Breaking a cycle by handing something nothing is not a fix</b>, and
     * this is the assertion that tells the two apart: {@code
     * EventChannelConfig} answers a context that assembled no areas with a
     * router claiming no type, which is correct there and would be a silent
     * disaster here — every frame on a fully wired server answered {@code
     * NOT_FOUND}. The handler is asked for its router the way a frame asks, and
     * it has to be the container's.
     */
    @Test
    void the_first_frame_finds_the_table_the_container_built() {
        try (AnnotationConfigApplicationContext context = boot()) {
            EventChannelHandler handler = context.getBean(EventChannelHandler.class);

            assertSame(context.getBean(FrameRouter.class), handler.router(),
                    "the handler routes through the assembled table and not through the"
                            + " claims-nothing fallback EventChannelConfig falls back to");
            assertSame(handler.router(), handler.router(),
                    "and asks for it once, so two frames cannot answer through two tables");
        }
    }

    /**
     * That a subscription recorded through one door is visible at the other.
     *
     * <p><b>This is the test the token stream needed and did not have.</b>
     * {@code EventChannelHandler} once had a constructor that defaulted {@link
     * Watchers} to {@code new Watchers()}, {@code EventChannelConfig} took it,
     * and {@code JobStreamHandler} took the {@code @Component} — so the object
     * a `job.stream` frame wrote to was never the object {@code
     * streaming(session)} read from. The answer was false forever, no delta was
     * ever produced, and the whole feature was inert in production while every
     * unit test passed, because every unit test builds both halves itself.
     *
     * <p>Driven end to end against a live server before it was noticed: the
     * client subscribed, the run answered, and the live preview stayed empty.
     * <b>Nothing failed anywhere.</b> That is what this asserts against.
     */
    @Test
    void a_subscription_written_by_a_frame_is_read_by_the_channel() {
        try (AnnotationConfigApplicationContext context = boot()) {
            Watchers watchers = context.getBean(Watchers.class);
            assertReal(watchers, "Watchers",
                    "the one place a token subscription is recorded");
            EventChannelHandler handler = context.getBean(EventChannelHandler.class);

            // What `JobStreamHandler` does when a client sends `job.stream`.
            watchers.wants("a-session", true);

            assertTrue(handler.streaming("a-session"),
                    "the channel cannot see a subscription the frame handler recorded, so"
                            + " the two hold different Watchers and no delta will ever be"
                            + " produced — which is exactly how this shipped inert");
            watchers.wants("a-session", false);
            assertFalse(handler.streaming("a-session"),
                    "and it cannot see the subscription being withdrawn either");
        }
    }

    /**
     * The log's writers outside a turn — a fold and a learning pass — are handed the push that
     * tells its followers by the container itself.
     *
     * <p>{@code AppendedConfigTest} calls the config by hand with the beans it made, which is
     * where a wiring that reached no real writer would still pass: a provider that resolved
     * nothing hands the push to nobody, says nothing, and every follower of a conversation a
     * fold or a pass wrote to hears nothing. So the real beans are asked what they were handed.
     */
    @Test
    void the_log_s_writers_outside_a_turn_are_handed_the_push_that_tells_followers() {
        try (AnnotationConfigApplicationContext context = boot()) {
            ConversationAppended appended = context.getBean(ConversationAppended.class);
            Compaction compaction = context.getBean(Compaction.class);
            Learner learner = context.getBean(Learner.class);
            assertReal(compaction, "Compaction", "a stand-in keeps no push to be asked about");
            assertReal(learner, "Learner", "a stand-in keeps no push to be asked about");

            assertSame(appended, ReflectionTestUtils.getField(compaction, "growth"),
                    "a fold's summary and a turn's close would reach no follower");
            assertSame(appended, ReflectionTestUtils.getField(learner, "growth"),
                    "a learning pass's note would reach no follower");
        }
    }

    /**
     * That a bean the loop is made of is the container's own, and not one of
     * {@link #substituting()}'s stand-ins.
     *
     * <p>Asserted rather than assumed, because {@code createBean} hands back a
     * mock for any bean whose factory throws non-cyclically — the substitution
     * that makes a database-less context possible, and the one that can empty
     * this test out. {@code getBean} answers such a stand-in exactly as it
     * answers the real thing, and every other assertion in this file is
     * satisfied by one.
     *
     * @param bean what the container answered
     * @param named the bean, as the failure should name it
     * @param why what the graph loses when this one is a stand-in
     */
    private static void assertReal(Object bean, String named, String why) {
        assertNotNull(bean, named + " is not in the scanned context at all");
        assertFalse(mockingDetails(bean).isMock(),
                named + " is a stand-in and not the bean the container built, so the graph"
                        + " this test measured is not the one that boots: " + why
                        + ". Look at what its constructor threw -- substituting() answers a"
                        + " mock for any bean whose factory fails non-cyclically.");
    }

    /** The real scan of both packages, over a factory that stands in for a database. */
    private static AnnotationConfigApplicationContext boot() {
        AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext(substituting());
        // false, as SpringApplication sets it: a cycle Spring papers over with an
        // early reference is still a cycle, and this test would stop seeing it.
        context.setAllowCircularReferences(false);
        context.scan(SCANNED);
        context.refresh();
        return context;
    }

    private static DefaultListableBeanFactory substituting() {
        return new DefaultListableBeanFactory() {

            @Override
            public Object resolveDependency(DependencyDescriptor descriptor, String requesting,
                    Set<String> autowired, TypeConverter converter) {
                try {
                    return super.resolveDependency(descriptor, requesting, autowired, converter);
                } catch (NoSuchBeanDefinitionException absent) {
                    return mockOf(descriptor.getDependencyType(), absent);
                }
            }

            @Override
            protected Object createBean(String name, RootBeanDefinition definition, Object[] args)
                    throws BeansException {
                try {
                    return super.createBean(name, definition, args);
                } catch (BeansException failed) {
                    if (cyclic(failed)) {
                        throw failed;
                    }
                    return mockOf(definition.getResolvableType().toClass(), failed);
                }
            }
        };
    }

    /** Whether a refusal is the one this test is looking for. */
    private static boolean cyclic(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof BeanCurrentlyInCreationException) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }

    /**
     * A stand-in of the given type.
     *
     * @throws IllegalStateException rethrowing {@code because}, if the type
     *     cannot be mocked — a value rather than a service, most likely, and
     *     silently dropping it would leave this test measuring less than it
     *     claims
     */
    private static Object mockOf(Class<?> type, Throwable because) {
        if (type == null || type.isPrimitive() || type.isArray() || type == Object.class) {
            throw new IllegalStateException("no stand-in for " + type, because);
        }
        try {
            return mock(type);
        } catch (RuntimeException unmockable) {
            throw new IllegalStateException("no stand-in for " + type.getName()
                    + ", which the scanned wiring needs: " + because.getMessage(), unmockable);
        }
    }
}
