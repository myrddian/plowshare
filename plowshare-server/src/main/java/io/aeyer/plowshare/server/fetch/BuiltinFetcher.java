package io.aeyer.plowshare.server.fetch;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLPeerUnverifiedException;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * {@link PageFetcher} over the public web: dial the {@code url} an agent asked to read, if it and
 * every hop it redirects through stay on public addresses, and hand back {@link
 * PageExtractor#extract}'s answer.
 *
 * <h2>Only the public web, checked twice on every hop</h2>
 *
 * <p>The server opens the connection, not the model. Without a guard, a URL an agent writes could
 * reach this server's own port, the network it sits on, or the cloud metadata address ({@code
 * implementation rationale}). Each hop is checked twice. First comes an early check: when the hop's
 * host is an IP literal, {@link AddressPolicy} judges it before any client is asked, so an obvious
 * refusal opens no socket. Then comes the check that counts: the client's {@link GuardedSockets}
 * judge the concrete address at connect time, whatever name it came from.
 *
 * <p>A hop matching a {@code plowshare.fetch.allow-private} entry is dialled through that entry's
 * client, whose sockets exempt its port on the private tier. Every other hop uses the strict
 * client. {@code FetchConfig} builds both kinds once, at boot. A refusal from either check is
 * {@link FetchFailure#REFUSED_ADDRESS}, worded with the hop's host and the tier, never the resolved
 * address.
 *
 * <h2>Redirects are followed here, not by OkHttp</h2>
 *
 * <p>Fetch's clients have {@code followRedirects(false)} and {@code followSslRedirects(false)}.
 * Only {@code 301}, {@code 302}, {@code 303}, {@code 307} and {@code 308} are redirects ({@link
 * #REDIRECTS}). Their {@code Location} goes to {@link Redirects#next}: at most five, never https to
 * http, never a non-http location. Every next hop goes back through both checks above. One of those
 * five with a missing or blank {@code Location} is a redirect that names nowhere, so {@link
 * FetchFailure#FETCH_FAILED}. Any other {@code 3xx}, {@code 304} and {@code 300} included, is not a
 * redirect at all: it is the non-2xx it is, so {@link FetchFailure#REMOTE_STATUS}, whatever {@code
 * Location} it carries. That is narrower than OkHttp's own {@code Response.isRedirect()}, which
 * counts {@code 300}. A multiple-choices answer is no single next page, and following its hint
 * would be a guess. The fetch as a whole, every hop included, keeps one {@code timeout} budget.
 * Every response, redirect hops included, is closed before the next hop is dialled, so no hop holds
 * a connection past its turn.
 *
 * <h2>A browser {@code User-Agent}, not this project's own name</h2>
 *
 * <p>The request identifies as a desktop browser rather than as {@code plowshare/...}, and that is
 * a retreat rather than the obvious choice: an honest agent string is what a reference
 * implementation this slice's spec describes actually shipped first, and abandoned, because a
 * meaningful slice of the open web either serves a bot-flagged request a degraded page, a CAPTCHA,
 * or a straight 403, or holds the connection open until this class's own timeout fires — a failure
 * this fetcher cannot tell apart from a dead host, and one an agent cannot route around by asking
 * again. Claiming to be {@code Mozilla} is the same trade every fetch-a-URL tool an agent might
 * otherwise be pointed at already makes, for the same reason: reading the page is the point, and a
 * truthful string that gets the read refused serves nobody it was honest with.
 *
 * <h2>Status before content type, deliberately</h2>
 *
 * <p>A non-2xx status is answered as {@link FetchFailure#REMOTE_STATUS} immediately, without the
 * response's {@code Content-Type} being consulted at all — a {@code 404} served as {@code
 * application/pdf}, or a {@code 503} with no headers at all (exactly what {@code
 * a_non_2xx_is_a_remote_status_failure_carrying_the_code} sends), is a remote status either way,
 * and what the body claims to be is not evidence about whether the remote succeeded. Checking
 * status first also means content type only ever has to answer a question that is meaningful: "is
 * this 2xx body something {@link PageExtractor} should read", which is not a question a failed
 * request's body needs to answer at all.
 *
 * <p>This ordering is pinned by {@code a_non_2xx_is_a_remote_status_failure_carrying_the_code} and
 * not merely stated: that test's {@code 503} carries no {@code Content-Type} header either, and
 * {@link #isHtml} answers {@code false} for a missing header regardless of what checked it first —
 * so checking content type ahead of status here would misclassify that exact response as {@link
 * FetchFailure#UNSUPPORTED_CONTENT_TYPE} instead of the {@link FetchFailure#REMOTE_STATUS} the test
 * requires. {@code a_2xx_with_no_content_type_and_a_non_html_body_is_refused} does <b>not</b> pin
 * this ordering the same way — its response is 2xx, so either ordering reaches the content-type
 * check and refuses it. What it pins instead is the next section's decision: that {@link #isHtml}
 * must answer {@code false} for a missing header at all, rather than {@code true}.
 *
 * <h2>Content type is required on the 2xx path, and checked before the body is parsed</h2>
 *
 * <p>{@link PageExtractor#extract} is a jsoup parse over whatever bytes it is handed, and jsoup
 * will produce <em>something</em> for any input, including a PDF's binary body read as Latin-1 tag
 * soup — no exception, no signal that the text it returned was never prose to begin with. So once a
 * response has cleared the status check above, this class checks {@code Content-Type} before it
 * asks for the body at all, and answers {@link FetchFailure#UNSUPPORTED_CONTENT_TYPE} straight from
 * the header. On this 2xx path a response that carries <b>no</b> {@code Content-Type} is refused
 * exactly as a present-but-wrong one is — {@code
 * a_2xx_with_no_content_type_and_a_non_html_body_is_refused} pins that.
 *
 * <h2>The per-call timeout is not baked into the clients</h2>
 *
 * <p>The clients this class holds are built once, at boot, by {@code FetchConfig}. Reaching into
 * one with {@code newBuilder().callTimeout(...)} per fetch would build and discard a whole client
 * on every call. {@link Call#timeout()} sets a deadline on the one call being made and touches
 * nothing else. Each hop's call gets whatever remains of this fetch's budget.
 *
 * <h2>Every failure is an answer, never an exception</h2>
 *
 * <p>See {@link FetchAnswer}'s own javadoc for the argument in full. It is why the body of {@link
 * #fetch} is one large {@code try} with a {@code catch (Exception e)} at the bottom. {@link
 * RefusedAddress} is recognised there by type, wherever OkHttp left it among causes and suppressed
 * exceptions, and becomes {@link FetchFailure#REFUSED_ADDRESS}. A {@link java.net.ConnectException}
 * or {@link javax.net.ssl.SSLPeerUnverifiedException} anywhere in the cause chain becomes {@link
 * FetchFailure#FETCH_FAILED} worded from the hop's own host and port rather than from {@code
 * getMessage()} — OkHttp's own message for either names the resolved address it dialled, and this
 * class refuses an address the same way everywhere else: never in a message a model reads.
 * Everything else becomes {@link FetchFailure#FETCH_FAILED} from {@code getMessage()} as before.
 */
public class BuiltinFetcher implements PageFetcher {

  /**
   * A current desktop Chrome-on-Windows string — see the class javadoc for why this class claims to
   * be a browser at all.
   */
  private static final String USER_AGENT =
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)"
          + " Chrome/124.0.0.0 Safari/537.36";

  private static final String ACCEPT =
      "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8";

  private static final String ACCEPT_LANGUAGE = "en-US,en;q=0.9";

  /**
   * The statuses followed as redirects. Not {@code 304}, which answers a conditional request this
   * fetcher never makes, and not {@code 300}; see the class javadoc (spec
   * 2026-09-28-fetch-stays-on-the-public-web §2.2).
   */
  static final Set<Integer> REDIRECTS = Set.of(301, 302, 303, 307, 308);

  private final OkHttpClient strict;
  private final Map<FetchAllowlist.Entry, OkHttpClient> exempt;
  private final FetchAllowlist allowlist;
  private final Duration timeout;

  /**
   * Built only by {@code FetchConfig.guardedFetcher}, which derives every client here once from the
   * shared one. Nothing else can build an unguarded fetcher.
   *
   * @param strict the client for a hop no allowlist entry matches: guarded sockets with no
   *     exemption, no redirects, no proxy
   * @param exempt one client per allowlist entry, the same as {@code strict} except that its
   *     sockets exempt that entry's port on the private tier
   * @param allowlist what decides, per hop, which of those clients dials it
   * @param timeout the budget for one whole fetch, every hop included
   */
  BuiltinFetcher(
      OkHttpClient strict,
      Map<FetchAllowlist.Entry, OkHttpClient> exempt,
      FetchAllowlist allowlist,
      Duration timeout) {
    this.strict = strict;
    this.exempt = Map.copyOf(exempt);
    this.allowlist = allowlist;
    this.timeout = timeout;
  }

  @Override
  public FetchAnswer fetch(String url) {
    HttpUrl hop = null;
    try {
      // HttpUrl.parse (unlike java.net.URI) only ever returns a value
      // for http and https, and null for anything else -- a file: URL,
      // a bare hostname with no scheme, a typo. That single call is
      // what refuses to dial before OkHttp ever gets a chance to try.
      HttpUrl parsed = HttpUrl.parse(url);
      if (parsed == null) {
        return FetchAnswer.failed(url, FetchFailure.FETCH_FAILED, "not an http(s) url: " + url);
      }

      long deadline = System.nanoTime() + timeout.toNanos();
      hop = parsed;
      for (int followed = 0; ; followed++) {
        Optional<AddressPolicy.Tier> early = refusedLiteral(hop);
        if (early.isPresent()) {
          return refused(url, hop, early.get());
        }
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
          return FetchAnswer.failed(
              url,
              FetchFailure.FETCH_FAILED,
              "timed out after " + timeout + " and " + followed + " redirects");
        }

        Call call = clientFor(hop).newCall(request(hop));
        call.timeout().timeout(remaining, TimeUnit.NANOSECONDS);

        try (Response response = call.execute()) {
          if (!REDIRECTS.contains(response.code())) {
            return answer(url, response);
          }
          String location = response.header("Location");
          if (location == null || location.isBlank()) {
            return FetchAnswer.failed(
                url,
                FetchFailure.FETCH_FAILED,
                "HTTP "
                    + response.code()
                    + " from "
                    + hop.host()
                    + " redirected with no Location to follow");
          }
          switch (Redirects.next(hop, location, followed)) {
            case Redirects.Stop stop -> {
              return FetchAnswer.failed(url, FetchFailure.FETCH_FAILED, stop.message());
            }
            case Redirects.Follow follow -> hop = follow.next();
          }
        }
      }
    } catch (Exception e) {
      Optional<RefusedAddress> refusal = refusalIn(e);
      if (refusal.isPresent() && hop != null) {
        return refused(url, hop, refusal.get().tier());
      }
      if (hop != null && isConnectFailure(e)) {
        return FetchAnswer.failed(
            url,
            FetchFailure.FETCH_FAILED,
            "could not connect to " + hop.host() + ":" + hop.port());
      }
      String detail =
          e.getMessage() == null || e.getMessage().isBlank()
              ? e.getClass().getSimpleName()
              : e.getMessage();
      return FetchAnswer.failed(url, FetchFailure.FETCH_FAILED, detail);
    }
  }

  /**
   * The client that dials {@code hop}: the allowlist entry's own, when the hop matches one on host
   * and effective port, and the strict one otherwise. Package-private so {@code BuiltinFetcherTest}
   * can see which client a hop gets.
   */
  OkHttpClient clientFor(HttpUrl hop) {
    return allowlist.match(hop).map(exempt::get).orElse(strict);
  }

  /**
   * The first {@link RefusedAddress} among {@code thrown}, its causes and their suppressed
   * exceptions. When every route fails, OkHttp throws the <em>first</em> route's failure with the
   * later ones suppressed, so a refusal is not always on top.
   */
  static Optional<RefusedAddress> refusalIn(Throwable thrown) {
    Deque<Throwable> pending = new ArrayDeque<>();
    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    pending.push(thrown);
    while (!pending.isEmpty()) {
      Throwable next = pending.pop();
      if (!seen.add(next)) {
        continue;
      }
      if (next instanceof RefusedAddress refusal) {
        return Optional.of(refusal);
      }
      if (next.getCause() != null) {
        pending.push(next.getCause());
      }
      for (Throwable suppressed : next.getSuppressed()) {
        pending.push(suppressed);
      }
    }
    return Optional.empty();
  }

  /**
   * {@code true} when {@code thrown} or one of its causes is a {@link ConnectException} or an
   * {@link SSLPeerUnverifiedException} — the two shapes whose own {@code getMessage()} names the
   * resolved address OkHttp dialled (e.g. {@code "Failed to connect to
   * docs.internal/10.0.0.5:8080"}), which would hand a model an internal address this guard exists
   * to keep it from learning. The {@link #fetch} catch block uses this to word its {@link
   * FetchFailure#FETCH_FAILED} from the hop's own host instead. Causes only, not suppressed
   * exceptions: unlike {@link #refusalIn}, which must find a refusal OkHttp may have buried behind
   * a later route's failure, this only needs to know whether the one exception actually thrown
   * carries an address-bearing message anywhere in its chain.
   */
  private static boolean isConnectFailure(Throwable thrown) {
    for (Throwable next = thrown; next != null; next = next.getCause()) {
      if (next instanceof ConnectException || next instanceof SSLPeerUnverifiedException) {
        return true;
      }
    }
    return false;
  }

  /**
   * The early check: the tier that refuses {@code hop} when its host is an IP literal, or empty
   * when the host is a name or the literal is allowed. A name is left to the connect-time check,
   * because only there is it an address.
   */
  private Optional<AddressPolicy.Tier> refusedLiteral(HttpUrl hop) {
    Optional<InetAddress> literal = AddressPolicy.literal(hop.host());
    if (literal.isEmpty()) {
      return Optional.empty();
    }
    OptionalInt exemptPort =
        allowlist.match(hop).map(entry -> OptionalInt.of(entry.port())).orElse(OptionalInt.empty());
    AddressPolicy.Verdict verdict = AddressPolicy.judge(literal.get(), hop.port(), exemptPort);
    return verdict.allowed() ? Optional.empty() : Optional.of(verdict.tier());
  }

  /** The host as the hop spelled it and the tier. Never the address. */
  private static FetchAnswer refused(String url, HttpUrl hop, AddressPolicy.Tier tier) {
    return FetchAnswer.failed(
        url, FetchFailure.REFUSED_ADDRESS, "refused: " + hop.host() + " is " + tier.phrase());
  }

  private static Request request(HttpUrl hop) {
    return new Request.Builder()
        .url(hop)
        .get()
        .header("User-Agent", USER_AGENT)
        .header("Accept", ACCEPT)
        .header("Accept-Language", ACCEPT_LANGUAGE)
        .build();
  }

  /** A response that is not a redirect to follow: status, then content type, then body. */
  private static FetchAnswer answer(String url, Response response) throws IOException {
    if (!response.isSuccessful()) {
      return FetchAnswer.failed(url, FetchFailure.REMOTE_STATUS, "HTTP " + response.code());
    }
    String contentType = response.header("Content-Type");
    if (!isHtml(contentType)) {
      return FetchAnswer.failed(
          url, FetchFailure.UNSUPPORTED_CONTENT_TYPE, "content-type is " + contentType);
    }
    byte[] raw =
        response.body() == null
            ? new byte[0]
            : response.body().byteStream().readNBytes(32 * 1024 * 1024 + 1);
    if (raw.length > 32 * 1024 * 1024)
      return FetchAnswer.failed(
          url, FetchFailure.FETCH_FAILED, "response exceeds the 32 MiB retained-source limit");
    java.nio.charset.Charset charset =
        response.body() == null || response.body().contentType() == null
            ? java.nio.charset.StandardCharsets.UTF_8
            : response.body().contentType().charset(java.nio.charset.StandardCharsets.UTF_8);
    String body = new String(raw, charset);
    // response.request().url() rather than the url parameter: after a
    // redirect, the response answers a different URL than the one this
    // fetch was asked for, and that is the base PageExtractor needs to
    // resolve a relative href or src correctly.
    String finalUrl = response.request().url().toString();
    return new FetchAnswer(
        url, PageExtractor.extract(body, finalUrl), null, null, raw, contentType, finalUrl);
  }

  /**
   * {@code true} only for {@code text/html} or {@code application/xhtml+xml} — never for a missing
   * header. See the class javadoc's section on the 2xx path for why absence is refused here rather
   * than assumed to be HTML.
   *
   * <p>Parsed with {@link MediaType} into a type and a subtype, rather than a substring search over
   * the raw header, so a value like {@code application/octet-stream; name="page.html"} — a real
   * content type this class must refuse — does not read as HTML merely for containing the letters
   * {@code html} somewhere in a parameter it was never this class's business to inspect. {@link
   * MediaType#type()} and {@link MediaType#subtype()} are compared with {@link Locale#ROOT}
   * lower-casing even though OkHttp already normalises both to lower case while parsing, because
   * this method's own contract should not depend on that detail of a class it does not own
   * remaining true.
   */
  private static boolean isHtml(String contentType) {
    if (contentType == null) {
      return false;
    }
    MediaType mediaType = MediaType.parse(contentType);
    if (mediaType == null) {
      return false;
    }
    String type = mediaType.type().toLowerCase(Locale.ROOT);
    String subtype = mediaType.subtype().toLowerCase(Locale.ROOT);
    return ("text".equals(type) && "html".equals(subtype))
        || ("application".equals(type) && "xhtml+xml".equals(subtype));
  }
}
