package io.aeyer.plowshare.server.information;

import com.fasterxml.jackson.databind.json.JsonMapper;
import io.aeyer.plowshare.protocol.Information;
import io.aeyer.plowshare.protocol.InformationDiscovery;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.information.InformationDiscoveryRepository.Position;
import io.aeyer.plowshare.server.information.InformationDiscoveryRepository.Reader;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Keyset pages share no result cache or authority lease. Cursors bind the principal, confinement,
 * operation and canonical selection; they expire after fifteen minutes or a server restart. Inserts
 * ahead of a cursor do not shift subsequent pages. Edits, revocation and new heads remain live, so
 * continuation is deliberately not a frozen snapshot.
 */
public final class CursorInformationDiscovery implements InformationDiscoveryService {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Base64.Encoder ENCODE = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DECODE = Base64.getUrlDecoder();
  private final InformationDiscoveryRepository repository;
  private final Clock clock;
  private final byte[] secret = new byte[32];

  public CursorInformationDiscovery(InformationDiscoveryRepository repository, Clock clock) {
    this.repository = Objects.requireNonNull(repository);
    this.clock = Objects.requireNonNull(clock);
    new SecureRandom().nextBytes(secret);
  }

  private record Cursor(String binding, String at, String key, long expires) {}

  public InformationDiscovery.Scopes scopes(
      Reader reader, InformationDiscovery.ScopesRequest request) {
    String binding = binding(reader, "scopes", "");
    var cursor = cursor(request.cursor(), binding);
    int limit = limit(request.limit());
    var rows = repository.scopes(reader, cursor == null ? null : cursor.key(), limit + 1);
    boolean more = rows.size() > limit;
    var page = rows.subList(0, Math.min(limit, rows.size()));
    String next = more ? next(binding, null, page.getLast().id(), cursor) : null;
    return new InformationDiscovery.Scopes(page, next, more);
  }

  public InformationDiscovery.Page list(Reader reader, InformationDiscovery.Query query) {
    if (query.query() != null || query.facet() != null)
      throw new CallerFault("Use information_search or information_facets");
    return page(reader, query, "list");
  }

  public InformationDiscovery.Page search(Reader reader, InformationDiscovery.Query query) {
    if (query.query() == null || query.facet() != null)
      throw new CallerFault("Information search requires query");
    return page(reader, query, "search");
  }

  private InformationDiscovery.Page page(
      Reader reader, InformationDiscovery.Query query, String operation) {
    String binding = queryBinding(reader, operation, query);
    var cursor = cursor(query.cursor(), binding);
    Position after = cursor == null ? null : new Position(Instant.parse(cursor.at()), cursor.key());
    int limit = limit(query.limit());
    var rows =
        repository.list(
            reader, query, InformationDiscoveryInputs.filter(query.filter()), after, limit + 1);
    boolean more = rows.size() > limit;
    var page = rows.subList(0, Math.min(limit, rows.size()));
    String next =
        more
            ? next(
                binding,
                page.getLast().createdAt().toString(),
                page.getLast().revision().toString(),
                cursor)
            : null;
    return new InformationDiscovery.Page(query.scopes(), page, next, more);
  }

  public InformationDiscovery.Facets facets(Reader reader, InformationDiscovery.Query query) {
    if (query.facet() == null || query.query() != null)
      throw new CallerFault("Information facets requires facet");
    String binding = queryBinding(reader, "facets", query);
    var cursor = cursor(query.cursor(), binding);
    int limit = limit(query.limit());
    var rows =
        repository.facets(
            reader,
            query,
            InformationDiscoveryInputs.filter(query.filter()),
            cursor == null ? null : cursor.key(),
            limit + 1);
    boolean more = rows.size() > limit;
    var page = rows.subList(0, Math.min(limit, rows.size()));
    return new InformationDiscovery.Facets(
        query.scopes(),
        query.facet(),
        page,
        more ? next(binding, null, page.getLast().value(), cursor) : null,
        more);
  }

  public Information.Window read(Reader reader, InformationDiscovery.Read request) {
    var retained = repository.read(reader, request);
    String text = retained.text();
    if (text == null)
      throw new CallerFault("Retained extraction is unavailable; inspect processing status");
    int start = request.offset() == null ? 0 : request.offset();
    if (start > text.length()) throw new CallerFault("Read offset is beyond retained text");
    int end =
        (int)
            Math.min(
                (long) start + (request.limit() == null ? 8192 : request.limit()), text.length());
    return new Information.Window(
        retained.revision(),
        null,
        null,
        start,
        end,
        text.length(),
        text.substring(start, end),
        retained.documentType(),
        retained.subtype());
  }

  private String queryBinding(Reader reader, String operation, InformationDiscovery.Query query) {
    // Page size can change; query meaning cannot. The cursor itself is excluded from its binding.
    return binding(
        reader,
        operation,
        new InformationDiscovery.Query(
            query.scopes(), query.filter(), query.query(), query.facet(), null, null));
  }

  private String binding(Reader reader, String operation, Object selection) {
    return InformationCatalogue.sha256(
        json(
            List.of(
                reader.actor(),
                reader.boundaryHome() == null ? "" : reader.boundaryHome(),
                operation,
                selection)));
  }

  private Cursor cursor(String token, String binding) {
    if (token == null) return null;
    try {
      if (token.length() > 2092) throw invalid();
      String[] parts = token.split("\\.", -1);
      if (parts.length != 2) throw invalid();
      byte[] payload = DECODE.decode(parts[0]);
      if (!MessageDigest.isEqual(mac(payload), DECODE.decode(parts[1]))) throw invalid();
      var cursor = JSON.readValue(payload, Cursor.class);
      if (!binding.equals(cursor.binding())
          || clock.instant().getEpochSecond() >= cursor.expires()
          || cursor.key() == null) throw invalid();
      return cursor;
    } catch (IllegalArgumentException | IOException invalid) {
      throw invalid();
    }
  }

  private String next(String binding, String at, String key, Cursor prior) {
    byte[] payload =
        json(
            new Cursor(
                binding,
                at,
                key,
                prior == null
                    ? clock.instant().plusSeconds(900).getEpochSecond()
                    : prior.expires()));
    return ENCODE.encodeToString(payload) + "." + ENCODE.encodeToString(mac(payload));
  }

  private byte[] mac(byte[] payload) {
    try {
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret, "HmacSHA256"));
      return mac.doFinal(payload);
    } catch (GeneralSecurityException unavailable) {
      throw new IllegalStateException("Discovery cursor authentication unavailable", unavailable);
    }
  }

  private static byte[] json(Object value) {
    try {
      return JSON.writeValueAsBytes(value);
    } catch (IOException invalid) {
      throw new IllegalStateException("Cannot encode discovery continuation", invalid);
    }
  }

  private static int limit(Integer value) {
    return value == null ? 10 : value;
  }

  private static CallerFault invalid() {
    return new CallerFault(
        "Invalid, expired or mismatched discovery cursor; restart without cursor");
  }
}
