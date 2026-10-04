package io.aeyer.plowshare.client;

import io.aeyer.plowshare.client.mcp.StdioTransport;
import io.aeyer.plowshare.client.mcp.ToolRegistry;
import io.aeyer.plowshare.client.tools.AgentTools;
import io.aeyer.plowshare.client.tools.AskTools;
import io.aeyer.plowshare.client.tools.ConversationTools;
import io.aeyer.plowshare.client.tools.DocumentTools;
import io.aeyer.plowshare.client.tools.FetchTools;
import io.aeyer.plowshare.client.tools.MemoryTools;
import io.aeyer.plowshare.client.tools.PresenceTools;
import io.aeyer.plowshare.client.tools.ProjectTools;
import io.aeyer.plowshare.client.tools.RetrieveTools;
import io.aeyer.plowshare.client.tools.SearchTools;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The process a harness spawns: MCP on stdin and stdout, HTTP to the server, and — once somebody
 * says where it is — a file channel serving this machine's own disk.
 *
 * <p><b>That last clause used to read "and nothing else", and it was the whole of the asymmetry the
 * parity design named.</b> This main built only an {@link HttpServerClient} and never a {@link
 * SessionClient}, so a run it started carried no session and reached the server's own filesystems:
 * a foreign harness could start a Plowshare agent, and that agent could not read a file on the
 * harness's machine. {@link ClientPresence} is the half that was missing, and nothing about it
 * happens until a person asks for it — see that class and {@code PresenceTools}.
 *
 * <p>Not in the plan's file list for this task, and added because the slice's own acceptance
 * criterion — "a harness configured against {@code plowshare-client} can write a memory and recall
 * it by meaning" — needs something to spawn. Without a main class the module is a library that only
 * its own tests can drive.
 *
 * <p>Holds no durable state and runs no inference, which is the whole of the client's contract:
 * every fact lives on the server, and the only model in Plowshare is the one the server calls to
 * turn text into a vector.
 *
 * <p>A harness spawns it as a plain class on a classpath — {@code java -cp <jars>
 * io.aeyer.plowshare.client.PlowshareClient} — with {@link #SERVER_URL_ENV} set if the server is
 * not where {@link #DEFAULT_SERVER_URL} says. There is deliberately no executable jar: {@code jar}
 * here packages this module alone, and a {@code java -jar} that failed on a missing Jackson would
 * be a worse first experience than a classpath that is written out.
 */
public final class PlowshareClient {

  private static final Logger log = LoggerFactory.getLogger(PlowshareClient.class);

  /**
   * The environment variable a harness sets to point this process at a server. Named after the
   * module, not after the protocol, because a box running Anchor as well will already have HTTP-ish
   * variables set.
   */
  public static final String SERVER_URL_ENV = "PLOWSHARE_SERVER_URL";

  /**
   * Matches {@code application.yml}'s {@code server.port} default of 8091, which is one digit up
   * from Anchor's 8090 so both run on one box.
   */
  public static final String DEFAULT_SERVER_URL = "http://localhost:8091";

  /**
   * The environment variable holding an access token for a server whose {@code
   * plowshare.auth.enabled} is true, which is every server by default since slice 4 task 7.
   *
   * <p><b>An environment variable and deliberately no system-property fallback</b>, which is where
   * this diverges from {@link #serverUrl()} one method down. A system property is set with {@code
   * -D} on a command line, and a command line is readable by every process on the box through
   * {@code ps}. A URL there is a configuration detail; a token there is the credential itself.
   *
   * <p><b>It is no longer the only way to have one, and this paragraph was swept.</b> It read
   * "Nothing in this repository can obtain one yet ... a person who needs this client against a
   * build from that window sets {@code PLOWSHARE_AUTH_ENABLED=false} on the server". Slice 4 task 8
   * closed that window: the server writes an operator token to {@link #consoleTokenFile()} at mode
   * 600 when it starts, and {@link #accessToken()} reads it. This variable is now the override —
   * for a server on another machine, or for a token an operator obtained some other way — and it
   * wins over the file.
   */
  public static final String TOKEN_ENV = "PLOWSHARE_TOKEN";

  /**
   * The system property that moves {@link #consoleTokenFile()}, for a test that must not read or
   * write the operator's real one.
   *
   * <p><b>A path, and never a token</b> — which is exactly the distinction {@link #TOKEN_ENV} above
   * refuses to blur. A system property is set with {@code -D} on a command line and every process
   * on the box can read a command line through {@code ps}; where a file lives is a configuration
   * detail and safe there, and what is in it is not.
   */
  public static final String TOKEN_FILE_PROPERTY = "plowshare.console.token.file";

  private PlowshareClient() {}

  public static void main(String[] args) throws IOException {
    String baseUrl = serverUrl();
    // stderr, via logback.xml. Nothing in this process may print to stdout:
    // that is the protocol channel, and one non-JSON-RPC line on it
    // desynchronises the harness for the rest of the session.
    log.info("plowshare mcp client starting, archive at {}", baseUrl);

    WsServerClient server = new WsServerClient(baseUrl, accessToken());
    // The half of a session this process never had. It dials nothing until
    // somebody calls client_root_project_here: rooting is explicit and
    // user-initiated, because a harness that spawned this must not have
    // whatever directory it was launched in lent on its behalf. See
    // ClientPresence and PresenceTools.
    try (server;
        ClientPresence presence = new ClientPresence(server, accessToken())) {
      // No reachability check at startup, deliberately, and the presence
      // above keeps it that way rather than merely leaving it alone. A
      // client that refused to start against a server that was still
      // coming up would be a harness that shows no memory tools at all,
      // which reads to the model as an archive that does not exist; a tool
      // call made while the server is down instead answers with a message
      // saying so, and the next one succeeds.
      new StdioTransport().serve(System.in, System.out, tools(server, presence));
    }
    // Outside the block: the presence is given back before this is said, so
    // the sentence is true when it is written rather than a moment early.
    log.info("stdin closed; plowshare mcp client exiting");
  }

  /**
   * The whole tool surface this process serves, assembled and checked.
   *
   * <p>Separate from {@link #main} for the reason {@code cli.Plowshare.run} is separate from its
   * own main: it returns the thing instead of serving it, so something other than a running process
   * can look at what was built. {@code ParityTest} builds this registry and compares it with the
   * CLI's command table, which is the only way to check the parity rule against what the two
   * surfaces really offer rather than against a list of what somebody believed they offered.
   *
   * <p>The last line is the one that makes parity structural: {@link Capabilities#toolsAreDeclared}
   * refuses a registry holding a tool nothing declares, so a family that grows a verb fails here —
   * at assembly, in every test that builds this — rather than drifting quietly away from the CLI.
   *
   * @param presence what this process roots. Passed to {@link AgentTools} so a run started here
   *     reaches the machine this client serves, and to {@link PresenceTools}, which is what roots
   *     it
   */
  public static ToolRegistry tools(ServerClient server, ClientPresence presence) {
    ToolRegistry registry = new ToolRegistry();
    new MemoryTools(server).registerOn(registry);
    new io.aeyer.plowshare.client.tools.DigestTools(server).registerOn(registry);
    // One ServerClient for all of them, so the tool families share a
    // connection pool and one base URL rather than disagreeing about which
    // server this process is talking to.
    //
    // And the presence, so a run started here reaches whatever machine this
    // client is serving. Before it was passed, agent_run had no session
    // because there was none to have, and an agent a foreign harness started
    // could not read a file on that harness's own disk.
    new AgentTools(server, presence).registerOn(registry);
    // The workspace-management surface, and the only place it exists. The
    // server binds none of these three as an agent tool, so a definition
    // naming one refuses the boot: the party that sets the leash must not be
    // a party the leash binds. See ProjectTools.
    new ProjectTools(server).registerOn(registry);
    // Reading a conversation back, which this surface could not do at all: a
    // foreign harness could start an agent and collect its result, and the
    // archive of folded histories, timings and failed attempts behind that
    // result was invisible to it. See ConversationTools.
    new ConversationTools(server).registerOn(registry);
    // The document corpus, and the parity rule is the whole reason it is
    // here: it is already reachable as an agent tool and as
    // POST /v1/documents/search, so without this a foreign harness could
    // start an agent that reads the corpus and could not read it itself.
    // Registered after the archive's tools and before the presence, because
    // the menu reads in registration order and this is the second corpus a
    // caller meets rather than the first.
    new DocumentTools(server).registerOn(registry);
    new io.aeyer.plowshare.client.tools.InformationTools(server).registerOn(registry);
    // A family of its own only because DocumentTools was owned by concurrent
    // work when the per-document ask landed; AskTools' javadoc says so and
    // says the fold is a one-line move. The registry is flat, so nothing on
    // the surface depends on which class registered which name.
    new AskTools(server).registerOn(registry);
    // The corpus as something a caller can look at rather than only query:
    // a passage with the paper around it, the list of what is here, and one
    // document's outline. A third family for AskTools' reason exactly --
    // DocumentTools was owned by concurrent work again -- and registered
    // beside it because the menu reads in registration order and these are
    // the reads a caller makes before it asks anything.
    new RetrieveTools(server).registerOn(registry);
    // One tool that reaches outside this project entirely -- the archive,
    // the corpus and a run all answer from what this deployment already
    // holds, and this is the one door onto anything else. Registered after
    // them for that reason, and before the presence tool below because it
    // acts on the server like every tool above it; PresenceTools is the
    // one exception, held for last on its own terms.
    new SearchTools(server).registerOn(registry);
    // The other door onto the open web, and registered right beside search
    // for the same reason DocumentTools and AskTools sit together above:
    // this is the export of a capability FetchTool already gives this
    // server's own agents in-process (design spec §10's internal-first
    // order), not a new one invented for this surface.
    new FetchTools(server).registerOn(registry);
    // Saying where this client is. Registered last because it is the one tool
    // here that acts on this process rather than on the server, and the menu
    // reads in registration order.
    new PresenceTools(presence).registerOn(registry);

    Capabilities.toolsAreDeclared(registry.tools().stream().map(ToolRegistry.Tool::name).toList());
    return registry;
  }

  /**
   * The environment first, then a system property so a test or a wrapper script can override
   * without exporting anything, then the default.
   *
   * <p>Public because {@code cli.Plowshare} resolves the same server the same way. One resolution
   * order for one variable: two processes in one module disagreeing about where the server is would
   * be a person setting {@link #SERVER_URL_ENV} and having half of it take effect.
   */
  public static String serverUrl() {
    String fromEnv = System.getenv(SERVER_URL_ENV);
    if (fromEnv != null && !fromEnv.isBlank()) {
      return fromEnv.trim();
    }
    return System.getProperty("plowshare.server.url", DEFAULT_SERVER_URL);
  }

  /**
   * The access token this process presents, or null if it has none.
   *
   * <p>Public because {@code cli.Plowshare} presents the same credential to the same server, for
   * the reason {@link #serverUrl()} is: one resolution for one variable, so a person who exports it
   * does not find half of it taking effect.
   *
   * <p><b>{@link #TOKEN_ENV} first, then {@link #consoleTokenFile()}.</b> The file is what a server
   * on this machine wrote when it started, and it is the ordinary case: run the server, run the
   * CLI, and neither command mentions a credential. The variable is the override, and it is first
   * so that pointing this client at a server on another machine is one export and not a file to
   * move out of the way.
   *
   * <p>Blank is null, for both sources. An exported-but-empty variable is a person who meant to set
   * one, and {@code Authorization: Bearer } with nothing after it is a request that fails with the
   * same 401 as no header at all — but through a path where the header exists, which is one more
   * thing to rule out while reading a capture. An unreadable or absent file is null for the same
   * reason it is not an error: a server that is not running on this machine has written nothing,
   * which is a fact about the deployment and not a fault.
   *
   * <p><b>Never logged.</b> {@link #main} logs the base URL at startup and must not grow a
   * companion line for this. Nor does the failure to read the file say anything: what it would
   * report is where a credential is not, and the 401 that follows is the message a person can act
   * on.
   */
  public static String accessToken() {
    String fromEnv = System.getenv(TOKEN_ENV);
    if (fromEnv != null && !fromEnv.isBlank()) {
      return fromEnv.trim();
    }
    return fromConsoleTokenFile();
  }

  /**
   * Where a Plowshare server on this machine writes the operator token: {@code
   * ~/.config/plowshare/console-token}, at mode 600.
   *
   * <p>The same path {@code AuthConfig.defaultTokenFile()} writes, spelled again here rather than
   * shared through {@code plowshare-protocol}: that module is the wire vocabulary the two halves
   * exchange, and where one machine keeps its own credential is not part of it. The two spellings
   * are pinned equal by {@code AuthControllerTest}, which has both modules on its classpath.
   *
   * <p>{@link #TOKEN_FILE_PROPERTY} moves it, and only a test sets that.
   */
  public static Path consoleTokenFile() {
    String override = System.getProperty(TOKEN_FILE_PROPERTY);
    if (override != null && !override.isBlank()) {
      return Path.of(override.trim());
    }
    return Path.of(System.getProperty("user.home"), ".config", "plowshare", "console-token");
  }

  /** What the file holds, or null for every way of not having it: absent, unreadable, or empty. */
  private static String fromConsoleTokenFile() {
    Path file = consoleTokenFile();
    try {
      // THE FIRST LINE, not the whole file. The server writes the operator
      // token there and the console URL — bootstrap token and all — on a
      // second line, because that URL had otherwise to be scraped out of the
      // log after every restart. Reading the whole file would hand the CLI
      // the two concatenated and authenticate nothing; the order is what
      // keeps this reader working, and AuthConfig's javadoc says so on the
      // writing side.
      String written =
          Files.readAllLines(file, StandardCharsets.UTF_8).stream().findFirst().orElse("").strip();
      return written.isEmpty() ? null : written;
    } catch (IOException | RuntimeException noCredentialHere) {
      // Silent, and the class note says why: the only thing this could
      // report is where a credential is not.
      return null;
    }
  }
}
