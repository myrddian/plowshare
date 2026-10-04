package io.aeyer.plowshare.client.files;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Legacy text-protocol compatibility. Since the owner's 2026-10-01 decision, source-capable file
 * channels send bounded bytes; server FileContents owns conversion and caching. SOURCE requests
 * never enter this class. It remains for direct legacy reads and servers that do not negotiate
 * source streaming.
 *
 * <p>The adaptor in front of a read: a format detected, converted once, and held so the next window
 * does not convert it again.
 *
 * <h2>The owner's design, and what it is not</h2>
 *
 * <blockquote>
 *
 * an adaptor to the front of the file_read operation — where the file type is detected -&gt;
 * converted to text -&gt; then the normal file_read ops work while its in the buffer. This buffer
 * would be like a cache if its evicted - it has to do that whole conversion again
 *
 * </blockquote>
 *
 * <p><b>Conversion is on this side because the files are on this side.</b> The client already opens
 * every file under the workspace leash; a server that had to convert would first have to be sent
 * bytes it has no business holding, and this is the same argument that keeps {@code ProjectTools}
 * on the MCP side.
 *
 * <p><b>The agent's API does not change, at all.</b> Same offsets, same windowing, same caps, same
 * continuation note, same refusals. Nothing in this package tells a model that a file was
 * converted, and {@code FileTools.READ_DESCRIPTION} one module over is untouched. That is not an
 * omission — it is the design: a read of a PDF is a read.
 *
 * <p><b>It does not replace multipart ingest and must not be used as one.</b> A window caps at
 * {@code Window.MAX_WINDOW_LINES} 2000 and {@code MAX_WINDOW_BYTES} 96 KiB, so pulling a book
 * through this for bulk ingest is many round trips of text nobody reads. {@code POST /v1/documents}
 * stays the answer for ingesting; this is the answer for reading.
 *
 * <h2>The invalidation key is the content hash, and that is a decision</h2>
 *
 * <p>The obvious key is the path plus its modified time, and it is <b>wrong here in a way that
 * produces wrong answers rather than slow ones</b>:
 *
 * <ul>
 *   <li>mtime is coarse. On a second-granular filesystem two writes inside one second are one
 *       timestamp, and the second one is served as the first for the rest of that second.
 *   <li>mtime is routinely <em>preserved</em> by the tools that change files under a running client
 *       — {@code rsync -t}, {@code cp -p}, {@code tar -p}, an unpacked archive, a restored backup.
 *       The content changes and the key does not.
 *   <li>A cache miss costs a re-conversion, which the owner's note already accepts. <b>A cache hit
 *       on changed content costs a model reading a document that is not there any more</b>, with
 *       nothing anywhere saying so. The two errors are not the same size.
 * </ul>
 *
 * <p>So the key is the SHA-256 of the bytes. <b>The bytes are read either way</b>: {@link
 * ClientEnforcer#decode} already reads the whole file on every call — it has to, because {@code
 * Span.totalLines} must be true and a strict decoder's promise is about the file rather than a byte
 * range — so hashing saves nothing by being skipped and adds one pass over memory this method
 * already holds. What the buffer saves is the <em>conversion</em>, which is the expensive half by
 * orders of magnitude: parsing a document is seconds, hashing it is milliseconds.
 *
 * <p>It also makes the key the one thing {@code TextExtraction} already said the seam would owe the
 * ingest path — <em>"the SHA-256 of the original bytes"</em> — so the exact hash is computed once
 * and lands in {@link Conversion} rather than being computed a second time by whoever needs it
 * next.
 *
 * <p><b>What it buys beyond correctness</b>, and it is not nothing: two paths that are one document
 * — a copy, a hard link, the same report checked into two worktrees — share one buffered
 * conversion, because the key is what the file <em>is</em> rather than where it sits.
 *
 * <h2>Format capability belongs to the client, and this build does not declare it</h2>
 *
 * <p>{@link #formats} is what this build can convert. It is a property of <b>the connected client
 * and not of the server</b>: a Java client with PDFBox and some other client without one support
 * different formats for the same project at the same moment, and no server can know which it is
 * talking to. Under presence a session already declares {@code ?session=&machine=&root=&project=}
 * on the file channel's upgrade, and this is the same kind of fact — something the client asserts
 * because only the client can.
 *
 * <p><b>It is deliberately not on that upgrade yet, and the reason is what it would be for.</b>
 * Nothing on the server would read it: {@code file_read} routes to a provider and asks, and the
 * answer to "can you read this" is the reply. A declared format list becomes load-bearing only for
 * something that must decide <em>before</em> asking — a tool description that named the formats a
 * particular run can read, or a router choosing between two attached clients — and neither exists.
 * Adding it now would put a field on the wire whose only property is that it is never wrong,
 * because nothing consults it. The seam is here, the list is here and tested, and the wire is one
 * query parameter away on the day something needs it.
 *
 * <h2>Concurrency</h2>
 *
 * <p>{@link ClientEnforcer} answers every request on its own virtual thread, so this is called from
 * several at once. The map is guarded and the <b>conversion is not</b>: two threads that ask for
 * the same unbuffered document both convert it and the second one's result replaces the first's,
 * which is a little wasted work exactly once. Converting under the lock would make one slow
 * document block every read of every other file, which is the wedge {@code ChannelClient} spends a
 * virtual thread per request to avoid.
 */
final class Conversions {

  /**
   * The converters this build has, in the order they are asked.
   *
   * <p>One, and the seam is the point rather than the breadth. A second format is a {@link
   * Converter} and a line here; everything that was hard — the key, the buffer, the refusal that
   * separates "this client does not read that" from "this document is broken", and the fact that
   * none of it is visible to a model — is already written and is not written per format.
   */
  private static final List<Converter> STANDARD = List.of(new PdfConverter());

  /**
   * The most text this buffer holds, in characters, across every conversion in it.
   *
   * <p>{@code ClientEnforcer.MAX_FILE_BYTES}' number, and the equality is the argument: that
   * constant is this module's judgement about the heap of whichever laptop is doing the reading,
   * and a buffer of conversions should cost about what one file at that ceiling already costs. It
   * is not read from that field, because the two bound different things — one is bytes of one file
   * on the way in, this is characters of several documents held afterwards — and a shared constant
   * would make a change to either mean a change to both.
   */
  static final int MAX_BUFFERED_CHARS = 8 * 1024 * 1024;

  private final List<Converter> converters;
  private final int allowance;

  /**
   * Access-ordered, so iteration starts at the least recently read and eviction is oldest-first
   * without a second structure to keep in step.
   */
  private final Map<String, Conversion> buffered = new LinkedHashMap<>(16, 0.75f, true);

  private int held;

  /** The converters this build ships, buffering {@link #MAX_BUFFERED_CHARS}. */
  Conversions() {
    this(STANDARD, MAX_BUFFERED_CHARS);
  }

  /**
   * @param converters asked in order. Copied, so a caller cannot add a format to a live client
   * @param allowance how many characters of conversion to hold. A test passes a small one so that
   *     eviction is reachable without eight megabytes of fixture; nothing in main passes anything
   *     but the constant
   */
  Conversions(List<Converter> converters, int allowance) {
    this.converters = List.copyOf(Objects.requireNonNull(converters, "converters"));
    if (allowance < 0) {
      throw new IllegalArgumentException("a buffer allowance cannot be negative");
    }
    this.allowance = allowance;
  }

  /**
   * What this client can convert, in a person's words.
   *
   * <p>See the class note on why this is not on the wire. It is a real declaration and not a debug
   * accessor: it is what a presence would send on the day something reads one, and {@code
   * ConversionsTest} pins it so that adding a converter without deciding what to call it fails
   * here.
   */
  List<String> formats() {
    List<String> named = new ArrayList<>(converters.size());
    for (Converter converter : converters) {
      named.add(converter.format());
    }
    return List.copyOf(named);
  }

  /**
   * These bytes as text, or {@code null} if they are not a format this client converts.
   *
   * <p><b>{@code null} is an ordinary answer and is not a refusal.</b> It means "this is not mine",
   * and the caller goes on to do what it did before this class existed — which for a PNG is the
   * strict UTF-8 decode that turns it away with the sentence it has always given. A refusal
   * invented here would replace a working answer with a new one for every binary in every
   * workspace.
   *
   * @param bytes the whole file, already read and already inside {@code
   *     ClientEnforcer.MAX_FILE_BYTES}
   * @throws Converter.Failed if this <em>is</em> a format this client converts and this particular
   *     document could not be converted. A fact about the document, which is why it is an exception
   *     and {@code null} is not
   */
  Conversion of(byte[] bytes) {
    Converter converter = converterFor(bytes);
    if (converter == null) {
      return null;
    }
    // Hashed only once something has claimed the bytes: a workspace full of
    // images should not pay for a digest of each of them to be told, again,
    // that nothing reads a PNG.
    String key = sha256(bytes);
    Conversion remembered = buffered(key);
    if (remembered != null) {
      return remembered;
    }
    Conversion made = new Conversion(converter.format(), key, folded(converter.toText(bytes)));
    // NOT refused for being large, and that is a decision rather than an
    // omission. ClientEnforcer refuses a FILE above its ceiling before
    // opening it, which is a check that prevents an allocation. A ceiling on
    // the CONVERSION can only be tested after the string exists, so it would
    // prevent nothing and would turn a document this client converted
    // correctly into a refusal. What it costs instead is buffer space, which
    // is what buffer() bounds -- an outsized conversion is served and not
    // held, and converting it again is the price of having read it.
    buffer(made);
    return made;
  }

  /**
   * Whether {@code key}'s conversion is held right now.
   *
   * <p>For a test that has to distinguish "the same text came back" from "the same text came back
   * <em>without</em> being converted again", which is the only claim a buffer makes and the one
   * thing an equality assertion cannot see.
   *
   * <p>{@code containsKey} and not {@code get}, and it matters: this map is access-ordered, so a
   * {@code get} would count as a read and move the entry to the young end. A test asking which
   * conversion is oldest must not change the answer by asking.
   */
  boolean holds(String key) {
    synchronized (buffered) {
      return buffered.containsKey(key);
    }
  }

  /** How many characters of conversion are held. */
  int heldChars() {
    synchronized (buffered) {
      return held;
    }
  }

  private Converter converterFor(byte[] bytes) {
    for (Converter converter : converters) {
      if (converter.recognises(bytes)) {
        return converter;
      }
    }
    return null;
  }

  private Conversion buffered(String key) {
    synchronized (buffered) {
      return buffered.get(key);
    }
  }

  /**
   * Hold this conversion, evicting the least recently read until it fits.
   *
   * <p>Not {@code removeEldestEntry}: that hook evicts at most one entry per insertion, and one
   * large conversion arriving after several small ones has to displace several. A conversion that
   * cannot fit even in an empty buffer is served and not held — buffering it would evict everything
   * else to make room for something that is evicted again on the next call, which is a cache that
   * makes the system slower than having none.
   */
  private void buffer(Conversion made) {
    synchronized (buffered) {
      Conversion replaced = buffered.remove(made.sourceSha256());
      if (replaced != null) {
        held -= replaced.weight();
      }
      if (made.weight() > allowance) {
        return;
      }
      for (Iterator<Conversion> oldest = buffered.values().iterator();
          held + made.weight() > allowance && oldest.hasNext(); ) {
        held -= oldest.next().weight();
        oldest.remove();
      }
      buffered.put(made.sourceSha256(), made);
      held += made.weight();
    }
  }

  /**
   * {@code \r\n} and a lone {@code \r} to {@code \n}, once, for every format.
   *
   * <p>{@code TextExtraction} folds the same three the same way and says why: it makes "a line" one
   * rule rather than three. Here it also stops a converter's own idea of a line separator from
   * reaching a window — a caller's lines must not depend on which library, or which platform's
   * default, produced them.
   */
  private static String folded(String text) {
    return text.replace("\r\n", "\n").replace('\r', '\n');
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
          .toLowerCase(Locale.ROOT);
    } catch (NoSuchAlgorithmException impossible) {
      // Every JVM ships SHA-256. TextExtraction says the same thing for
      // the same reason: this is not a condition a caller can act on, and
      // dressing it as a conversion failure would blame the document for
      // the platform.
      throw new IllegalStateException("SHA-256 is unavailable on this JVM", impossible);
    }
  }
}
