package io.aeyer.plowshare.server.ws;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * How a frame handler reads its payload — the one place spec §3.2's second half is implemented, and
 * the only decoder a handler in this package should ever need.
 *
 * <h2>§3.2: the envelope is exact and the payload is tolerant</h2>
 *
 * <p>"Both are right about different layers — the envelope is a handshake and the payload is data."
 * {@link io.aeyer.plowshare.protocol.frames.Envelope} enforces the first half: a frame naming the
 * wrong {@code protocol_version} does not become an envelope at all. This class is the second half:
 * a payload carrying a field this build has never heard of is a newer client talking to an older
 * server, and is read for the fields this server does know.
 *
 * <p><b>Why that is a property of the surface and not of each handler.</b> The two pilots each
 * implemented this tolerance for themselves — one carried its own {@code ObjectMapper} with {@code
 * FAIL_ON_UNKNOWN_PROPERTIES} disabled, the other hand-read a key off the map — and fifty more
 * handlers written the same way is fifty chances to omit one {@code .disable(...)} call. An omitted
 * one is not a crash: it is a 400 for one frame type, on a field the server was always free to
 * ignore, discovered by a client in the field rather than by this repository's tests. Routing every
 * handler's decode through here makes tolerance a thing the surface has rather than a thing each
 * author remembered, and {@code FrameShapeTest} is what keeps a handler from quietly acquiring a
 * decoder of its own.
 *
 * <h2>The payload key convention, stated where the breadth plan will read it</h2>
 *
 * <p>A frame's payload <b>is the endpoint's own request body, field for field</b>. A handler binds
 * it to the same {@code api/} request record the controller binds, through {@link #as}, so the two
 * surfaces cannot drift into two spellings of one request.
 *
 * <p>Where the endpoint took a value from its <em>path</em> rather than its body, the payload names
 * that value with <b>the noun of the thing</b> — {@code conversation}, {@code project}, {@code
 * document}, {@code job} — and <b>never {@code id}</b>. Two reasons, and the first is not a
 * preference: {@code id} at the envelope level already means the client-generated correlation a
 * response echoes, so a frame carrying {@code id} twice at two nesting levels means two different
 * things by one name. The second is that fifty handlers each choosing a spelling for "which one" is
 * fifty facts a client has to learn one at a time. {@link #required} refuses the key {@code "id"}
 * outright — as a programming error and not a caller fault — so this paragraph is enforced rather
 * than merely written down.
 *
 * <p>Where the endpoint took a value from a <em>query string</em> the same rule applies, under the
 * name the query parameter already had ({@code project}, {@code limit}), since that name is already
 * a client-facing spelling.
 */
public final class Payloads {

  /**
   * Deliberately as lenient as {@link FrameRouter}'s own envelope mapper, and for the reason the
   * class javadoc gives. One instance: {@code ObjectMapper} is thread-safe once configured, and a
   * handler is called on whichever inbound thread its frame arrived on.
   */
  private static final ObjectMapper JSON =
      new ObjectMapper()
          .findAndRegisterModules()
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

  private Payloads() {}

  /**
   * {@code payload}, read as the request record {@code shape} — the same record the endpoint binds
   * its body to.
   *
   * <p><b>An absent payload is read as an empty one</b> rather than refused here: {@code {}} and no
   * payload at all are the same request, one that named no fields, and whatever validates the
   * fields next already has the sentence for it — naming the field rather than the frame, which is
   * what makes it the endpoint's own sentence.
   *
   * <p><b>A payload of the wrong shape is the caller's mistake, and saying so is half of why this
   * method exists.</b> {@code convertValue} raises {@link IllegalArgumentException} for a field
   * bound to the wrong JSON type — a list sent as a string, say — and {@code Faults} has no entry
   * for that class at all, so an uncaught one would answer 500 for a typo while the endpoint
   * answers 400 for the same body. Translating it to a {@link CallerFault} is not a second status
   * mapping: the code still comes from {@code Faults}, which is where {@link CallerFault} has
   * always meant 400.
   *
   * @param payload the frame's own payload, possibly null
   * @param shape the request record both surfaces bind, typically one of {@code api/}'s
   * @param type the frame type being read, named in the refusal so a caller is told which frame it
   *     got wrong
   * @param <T> the request record's type
   * @throws CallerFault if {@code payload} cannot be read as {@code shape}
   */
  public static <T> T as(Map<String, Object> payload, Class<T> shape, String type) {
    try {
      return JSON.convertValue(payload == null ? Map.of() : payload, shape);
    } catch (IllegalArgumentException notThisShape) {
      throw new CallerFault(
          "that payload is not the shape "
              + type
              + " takes"
              + fields(shape)
              + ": "
              + notThisShape.getMessage(),
          notThisShape);
    }
  }

  /**
   * The string {@code key} names in {@code payload}, refused as a caller fault when it is missing,
   * blank, or not a string at all.
   *
   * <p>For the value an endpoint took from its path, which has no request record to bind and no
   * equivalent refusal on the HTTP side — a URL that names no conversation is a different URL, and
   * Spring answers it with its own 404 before a controller is reached. "You named none" is a
   * request only this surface can receive, so only this surface needs an answer for it, and a
   * {@link CallerFault} leaves {@code Faults} the one thing deciding that is a 400.
   *
   * @param payload the frame's own payload, possibly null
   * @param key the field's name — the noun of the thing, per this class's convention
   * @param type the frame type being read, named in the refusal
   * @param said what the caller should do instead, appended to the refusal — a sentence, not a
   *     fragment
   * @throws IllegalArgumentException if {@code key} is {@code "id"}, which the envelope already
   *     means; see the convention on this class
   * @throws CallerFault if the payload does not carry {@code key} as a non-blank string
   */
  public static String required(Map<String, Object> payload, String key, String type, String said) {
    if ("id".equals(key)) {
      throw new IllegalArgumentException(
          "a payload may not name a field \"id\": the"
              + " envelope's own id is the client's correlation, so "
              + type
              + " names"
              + " what it acts on by its noun -- see ws.Payloads' convention");
    }
    Object asked = payload == null ? null : payload.get(key);
    if (asked instanceof String named && !named.isBlank()) {
      return named;
    }
    throw new CallerFault(type + " needs its payload to say which: send '" + key + "' as " + said);
  }

  /**
   * The fields {@code shape} takes, as a phrase, or the empty string when {@code shape} is not a
   * record and cannot be asked.
   *
   * <p>Read off the record rather than written out by each handler, so the enumeration a refusal
   * offers cannot fall behind the record it describes — the same reason {@code Capabilities} reads
   * its own register rather than a fixture of it.
   */
  private static String fields(Class<?> shape) {
    RecordComponent[] components = shape.getRecordComponents();
    if (components == null || components.length == 0) {
      return "";
    }
    return " -- its fields are "
        + Arrays.stream(components).map(RecordComponent::getName).collect(Collectors.joining(", "));
  }
}
