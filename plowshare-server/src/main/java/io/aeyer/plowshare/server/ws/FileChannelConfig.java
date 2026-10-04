package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.files.SessionChannel;
import io.aeyer.plowshare.server.files.SessionCloseListener;
import io.aeyer.plowshare.server.session.PresenceRegistry;
import io.aeyer.plowshare.server.session.ProjectRoots;
import io.aeyer.plowshare.server.session.Role;
import io.aeyer.plowshare.server.session.SessionRegistry;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * Puts the file channel on a URL.
 *
 * <p>Not in the plan's file list for this task, and shipped anyway: a {@link FileChannelHandler}
 * nothing registers is a handler no client can reach and no test can drive over a real socket, so
 * the whole of the task would rest on fakes. The equivalent omission in {@code AgentsConfig} is
 * where this project has agreed wiring decisions are made, and this is the one wiring decision that
 * belongs to the thing being built rather than to task 10's pass.
 *
 * <p>The handler is one bean, so it is one {@link SessionChannel} for the whole server — every
 * job's {@code RemoteProvider} asks through it, and the session id is what selects the socket.
 * (This said "the registry is one bean" until there was a {@link SessionRegistry} in the same file
 * for that word to be read as.) That matches {@code AgentTool}'s rule from the other direction: the
 * shared object holds no run's state, and everything per-run is a parameter.
 *
 * <p><b>No {@code setAllowedOrigins}.</b> The default refuses cross-origin upgrades, and the client
 * here is a local process rather than a page. Opening it to {@code *} is the line that would let
 * any web page a developer visits open a channel to a server on their own machine and be handed a
 * session's files — so the absence of that call is load-bearing and is why it is mentioned rather
 * than left to be noticed.
 *
 * <p>Slice 4's {@code AuthFilter} is a second barrier and not a replacement for this one: it
 * refuses an upgrade to this path with a 401 unless the request carries the operator's access
 * token, and a page's transport for that token is a cookie the browser attaches on the page's
 * behalf. {@link EventChannelConfig} spells out why that makes the origin check the barrier that
 * does not depend on a cookie flag; the reasoning is the same here and the cost is a session's
 * files rather than its events.
 *
 * <p><b>It also holds the one message-size bound for this application.</b> {@link
 * #webSocketContainer()} is where the transport stopped being a container default and became a
 * decision; a reader who arrived here holding {@code CloseStatus[code=1009]} wants that bean's
 * javadoc and not this paragraph.
 */
@Configuration
@EnableWebSocket
public class FileChannelConfig implements WebSocketConfigurer {

  /**
   * The one registry, defined by {@link EventChannelConfig} and injected here.
   *
   * <p><b>Injected and never built.</b> That class's own javadoc says what a second registry would
   * be — "two sessions with one name, each holding half of what a job has to ask about" — and this
   * is the wiring it named as the one that would have to attach later. A handler holding a registry
   * of its own would fill {@link Role#FILE_PROVIDER} in a map no run ever reads.
   *
   * <p>A constructor parameter rather than a {@code @Bean} method argument, because {@link
   * #registerWebSocketHandlers} also needs it and calls {@link #fileChannelHandler()} through the
   * CGLIB proxy to get the one singleton. It is not a cycle: the bean it asks for is defined in
   * another configuration class, which asks this one for nothing.
   */
  private final SessionRegistry sessions;

  /**
   * Where a declaration's durable half goes.
   *
   * <p><b>The seam and not {@code ProjectStore}, and the reason is which module owns which
   * bean.</b> {@code AgentsConfig} builds the store, because {@code
   * AgentsConfig.definitionResolver} needs one of its own to ask {@code projects::exists} on every
   * project-tier cache miss; this configuration is the socket that presences are declared on.
   * Injecting the narrow interface keeps the dependency to what this file actually needs — one
   * method — and it is what lets {@code FileChannelTest} drive the whole production wiring with a
   * servlet container and no Postgres behind it, which is the arrangement that file has always had.
   */
  private final ProjectRoots roots;

  private final ProjectMembers members;

  /**
   * Every bean in the context that must forget a session when this configuration's handler closes
   * one — {@code DefinitionResolver} among them.
   *
   * <p>A constructor parameter for the same reason {@link #sessions} and {@link #roots} are: {@link
   * #fileChannelHandler} is called a second time, through the CGLIB proxy, by {@link
   * #registerWebSocketHandlers}, so anything the bean method needs has to be reachable from both
   * call sites without being resolved twice.
   *
   * <p>An {@link ObjectProvider} of the listener type itself — <b>not of a {@link List} of it</b>,
   * which is not the type-mismatch it looks like: Spring's collection auto-wiring (all beans of one
   * type gathered into a {@code List<T>}) is a property of injecting {@code List<T>} directly, and
   * an {@code ObjectProvider<List<T>>} asks it for a single bean whose type literally is {@code
   * List<SessionCloseListener>} — a bean nothing here ever declares. {@link
   * ObjectProvider#orderedStream()} is where the "every bean of this type" behaviour actually lives
   * on this type, and {@link #fileChannelHandler} calls it to build the list this field's name
   * promises.
   *
   * <p>An {@code ObjectProvider} at all, and not {@code List<SessionCloseListener>} injected
   * plainly, because at least one such listener — {@code DefinitionResolver} — is itself built from
   * the {@link SessionChannel} this configuration's own handler bean <em>is</em>. A plain {@code
   * List} Spring must fully resolve before this configuration object could even be constructed
   * would need that listener already built, which needs this configuration's handler bean already
   * built — a cycle in the object graph and not merely in the package diagram. {@link
   * FileChannelHandler}'s own {@code closeListeners} field javadoc carries the rest of this
   * argument. The stream above is not walked until {@link #fileChannelHandler}'s own adapting
   * {@code Supplier} runs, which is not until a socket actually closes.
   */
  private final ObjectProvider<SessionCloseListener> closeListeners;

  public FileChannelConfig(
      SessionRegistry sessions,
      ProjectRoots roots,
      ObjectProvider<SessionCloseListener> closeListeners,
      ProjectMembers members) {
    this.sessions = Objects.requireNonNull(sessions, "sessions");
    this.roots = Objects.requireNonNull(roots, "roots");
    this.members = members;
    this.closeListeners = Objects.requireNonNull(closeListeners, "closeListeners");
  }

  /**
   * The one handler, offered under the seam's name as well as its own.
   *
   * <p>{@code files/} depends on nothing in {@code ws/}, so {@code RemoteProvider} is built against
   * {@link SessionChannel} and this is where the two are tied together. Task 10 builds the
   * providers; what this task owes it is something it can inject as a {@link SessionChannel}, and
   * this bean is it — Spring matches an injection point by the bean's actual type, so a handler
   * that implements the interface needs no second bean under it.
   *
   * <p>There was one for a while, and it was wrong in a way worth recording: two bean
   * <em>names</em> for one object make {@code getBean(FileChannelHandler.class)} ambiguous, so the
   * test that drives the production wiring could not reach the thing the production wiring built.
   *
   * <p>Built with {@link #closeListeners} as well, adapted from an {@link ObjectProvider} of one
   * listener to the {@code Supplier<List<...>>} the fuller constructor takes — {@link
   * ObjectProvider#orderedStream()} is what actually gathers every bean of the type, and {@link
   * #closeListeners}'s own javadoc says why the field is not simply a {@link List} already.
   */
  @Bean
  public FileChannelHandler fileChannelHandler() {
    return new FileChannelHandler(
        sessions,
        presenceRegistry(),
        roots,
        members,
        FileChannelHandler.DEADLINE,
        () -> closeListeners.orderedStream().toList());
  }

  /**
   * Which live session roots which project, right now.
   *
   * <p><b>Defined here rather than beside {@link SessionRegistry} in {@link
   * EventChannelConfig}</b>, and the reason is which channel writes to it: a presence's lifetime is
   * a file channel's lifetime, so this is the configuration of the socket that owns it. The event
   * channel has no opinion about places — a browser holds a listener and never a disk, so it can
   * hold conversations and never root a project, which is the asymmetry the design spec's §8.4 asks
   * to be visible rather than surprising. Here it is visible: nothing in {@link EventChannelConfig}
   * touches this bean.
   *
   * <p>One bean, for {@link SessionRegistry}'s reason word for word. {@code
   * AgentsConfig.runProviders} reads it to decide which machine a run reaches and {@link
   * FileChannelHandler} writes it; two of them would be a presence declared where no run can see
   * it, and the failure is silent — every run in that project would quietly get the server's disk
   * and a sentence saying nothing roots a project somebody is sitting at.
   *
   * <p>A {@code @Bean} rather than a {@code @Component}, which keeps {@code server.session} free of
   * Spring: that package is exercised without a container, and a registry reachable only through a
   * socket would be an instrument that cannot be pointed at its own edges.
   */
  @Bean
  public PresenceRegistry presenceRegistry() {
    return new PresenceRegistry();
  }

  /**
   * How large an incoming text message either socket may be, in <em>chars</em>.
   *
   * <h2>What happens without it, verbatim</h2>
   *
   * <p>A client answered a read and the channel closed with
   *
   * <blockquote>
   *
   * {@code CloseStatus[code=1009, reason=The decoded text message was too big for the output buffer
   * and the endpoint does not support partial messages]}
   *
   * </blockquote>
   *
   * <p>The reason is quoted whole so that the next person holding that close code finds this
   * constant by searching for the string. The session is the object both sockets attach to, so it
   * died with the channel and the job ended {@code SESSION_GONE} — a file three times the size of a
   * mail attachment, read once, ending a run.
   *
   * <p><b>Nothing configured this before, and that was the whole fault.</b> Three bounds applied to
   * one read: {@code LocalProvider.MAX_FILE_BYTES} refused at 8 MiB, {@code
   * FileTools.MAX_DISPLAY_CHARS} truncated at 100 000 characters, and the transport carried 8 192 —
   * and the smallest of the three was the only one nobody had chosen. Measured, by deleting the
   * setter below and asking the container: {@code getDefaultMaxTextMessageBufferSize()} answers 8
   * 192, and a 1 000 043-char frame is closed with the reason above on <em>both</em> paths.
   *
   * <h2>The number comes from a measurement, not from the window ceiling</h2>
   *
   * <p>Sizing this from {@link io.aeyer.plowshare.protocol.Window#MAX_WINDOW_BYTES} would reproduce
   * the bug at a larger number. That constant counts the <em>text</em>; this one bounds the
   * <em>frame</em>, and JSON quotes every line, escapes what needs escaping, and puts a comma
   * between them. A full window serialised through {@code FileChannelHandler}'s own mapper, on JDK
   * 21 and Jackson 2.17, with {@code Window.cut} run to its byte ceiling:
   *
   * <ul>
   *   <li>ASCII letters — 98 300 bytes of text, 102 378 chars of frame;
   *   <li>quotes, backslashes or tabs, each a two-char escape — 198 712;
   *   <li>C0 control characters, which Jackson writes as a six-char {@code &#92;uXXXX} — <b>584
   *       048</b>;
   *   <li>accented, CJK and astral text — around 100 000 <em>bytes</em> of UTF-8 but only 34 000 to
   *       52 000 chars, which is why they are not the worst case here.
   * </ul>
   *
   * <p>Those are one harness's request id and one file's line count; {@code FileChannelTest}
   * recomputes the last row from {@code Window.cut} on every run and gets 584 050, because the id
   * it sends is three chars longer and its {@code totalLines} one digit shorter. <b>The exact count
   * is not the decision — the order of magnitude is</b>, which is why the pin is an assertion
   * against a recomputed frame and not against a number copied out of this comment.
   *
   * <p><b>The control-character row is the worst case and it is reachable.</b> Six chars out of one
   * byte of window budget is the largest expansion Jackson has, and nothing on either side of this
   * wire rejects a file for containing control characters: {@code ClientEnforcer} refuses a file
   * only for not decoding as UTF-8, and a C0 byte decodes fine. The ordinary spelling of it is not
   * a hostile fixture but a captured terminal log, whose every colour change is an {@code ESC} at
   * U+001B. The arithmetic ceiling for any window this protocol can cut is about 600 000 chars — 98
   * 304 bytes at six chars each, plus three chars of framing for each of at most 2 000 lines — so
   * the measurement and the bound agree.
   *
   * <h2>The headroom, and what it costs</h2>
   *
   * <p>1 MiB is 1.75x that ceiling. The headroom is deliberate and it is not free: <b>measured over
   * 25 open file channels, about 3.1 MB of heap each at this setting against about 0.14 MB each at
   * 8 192</b>, because the decode buffer is allocated per session rather than shared. That was a
   * throwaway probe rather than a standing test — it is an order of magnitude with the JVM's own
   * noise in it, and it is quoted because the trade is real and not because the third digit is.
   * Plowshare opens one file channel per operator session, so that is affordable; a deployment with
   * thousands of concurrent channels would have to make this trade again rather than inherit it.
   *
   * <p>What the headroom buys beyond the ceiling is the case below, which cannot be bounded
   * properly at all.
   *
   * <h2>The residual that was here, and where it went</h2>
   *
   * <p><b>{@code Window.cut} used to return an oversized first line whole</b>, so the frame for a
   * file whose first line is enormous was bounded by {@code MAX_FILE_BYTES} and not by {@code
   * MAX_WINDOW_BYTES}. Measured: a single two-million-character line serialises to a 2 000 134-char
   * frame, which this buffer does not carry, and a minified bundle is exactly that shape.
   *
   * <p>Sizing for it was never available. It means 8 MiB of ASCII, or 48 MiB once escaped, and
   * extrapolating the heap measurement above at roughly three bytes per configured char puts that
   * at over a hundred megabytes per open channel — which is not a buffer, it is an outage of a
   * different kind.
   *
   * <p><b>So the fix went where it belonged, which was not this number.</b> {@code Window.cut}
   * refuses a line wider than {@code MAX_WINDOW_BYTES} and names the search that still reads inside
   * such a file, and both halves of the wire run that one method. The consequence for this constant
   * is the one worth stating: <b>every frame the protocol can now produce is bounded by the
   * arithmetic above</b>, with no case standing outside it, so the ceiling and this bound are a
   * complete pair rather than a pair with a known hole.
   *
   * <h2>Chars, not bytes</h2>
   *
   * <p><b>Measured rather than assumed</b>, because the two readings differ by a factor of three
   * for the text worth reading: at this setting a frame of 700 043 chars weighing 1 400 043 bytes
   * of UTF-8 crosses intact. Tomcat decodes into a {@code CharBuffer} of this capacity, so the unit
   * is Java chars and non-ASCII text is cheaper here than its byte length suggests. The
   * measurements above are therefore quoted in chars.
   */
  private static final int MAX_TEXT_MESSAGE_CHARS = 1024 * 1024;

  /**
   * The bound for both sockets, because there is only one container.
   *
   * <p><b>{@link ServletServerContainerFactoryBean} is container-wide and not per-handler</b>, and
   * this was verified rather than assumed: with this bean present, a 1 000 043-char frame crosses
   * {@link EventChannelHandler#PATH} as well as {@link FileChannelHandler#PATH}, and with the
   * setter deleted both refuse it with the close reason quoted above. It sets the default on the
   * one {@code ServerContainer} the servlet context holds, which every endpoint deployed into that
   * context inherits.
   *
   * <p>So {@link EventChannelConfig} needs nothing of its own and deliberately has nothing: a
   * second bean of this type would be a duplicate definition of the same container, and a second
   * <em>number</em> for it would be the two disagreeing bounds this whole class exists to stop. The
   * event channel carries {@code JobEvent}s, which are small, so it is not what sized this — it is
   * a passenger, and saying so here is the point of putting the bean in the file whose traffic
   * chose the number.
   *
   * <p>The bean lives here rather than in a configuration of its own because this is the channel
   * the bound is for; a reader who arrives from the close code arrives at the file channel.
   */
  @Bean
  @Conditional(OnARealServletContainer.class)
  public ServletServerContainerFactoryBean webSocketContainer() {
    ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
    container.setMaxTextMessageBufferSize(MAX_TEXT_MESSAGE_CHARS);
    return container;
  }

  /**
   * Defined only where there is a websocket container to configure.
   *
   * <p><b>This is a guard against a startup failure, not a preference.</b> {@link
   * ServletServerContainerFactoryBean#afterPropertiesSet()} reads the {@code
   * jakarta.websocket.server.ServerContainer} attribute off the servlet context and throws when it
   * is absent, and it is absent in every {@code @SpringBootTest} that runs with the default {@code
   * MOCK} web environment: a {@code MockServletContext} never ran Tomcat's {@code WsSci}. Measured
   * — the unguarded bean took {@code TransactionBoundaryTest}'s whole context down with "Attribute
   * 'jakarta.websocket.server.ServerContainer' not found in ServletContext", seven tests that have
   * nothing to do with sockets.
   *
   * <p><b>The condition is on the context's type and not on that attribute</b>, and this is the
   * part that had to be run rather than reasoned about: conditions are evaluated by {@code
   * ConfigurationClassPostProcessor} during {@code invokeBeanFactoryPostProcessors}, while the
   * embedded container — and so the attribute — is created later, in {@code onRefresh}. A condition
   * that looked for the attribute would therefore be false in production too, and would have
   * shipped the default 8 192 with a bean in the file claiming otherwise. The context's class is
   * known at the earlier moment; the attribute is not. Measured, with the attribute-based condition
   * in place and a print in it: {@code realContext=true attributePresent=false} in {@code
   * FileChannelTest}'s own embedded container, and the pin below went red at 8 192.
   *
   * <p>What is lost where the condition is false is nothing: a context with no web server has no
   * socket to size a buffer for. What is <em>not</em> lost is the instrument — {@code
   * FileChannelTest} runs against a real embedded container, so the bean is present there and its
   * value is read back off the container it configured.
   */
  static final class OnARealServletContainer implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
      return context.getResourceLoader() instanceof ServletWebServerApplicationContext;
    }
  }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    // fileChannelHandler(), not a constructor parameter: a @Configuration
    // class that injected a bean it also defines is a cycle, and the
    // CGLIB proxy makes this call return the same singleton.
    // The account AuthFilter resolved goes onto the socket, as it does on
    // /v1/events, so a session id is held to one account (Enzo's decision
    // of 2026-09-30, spec 2026-09-30-local-hooks-are-served).
    registry
        .addHandler(fileChannelHandler(), FileChannelHandler.PATH)
        .addInterceptors(new HandleInterceptor());
  }
}
