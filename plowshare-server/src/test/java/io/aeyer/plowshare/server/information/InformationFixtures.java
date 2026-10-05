package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.server.archive.UnitOfWork;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.jdbc.core.JdbcTemplate;

/** Test composition for the mutually referring read and log-access capabilities. */
public final class InformationFixtures {
  private InformationFixtures() {}

  public static InformationCatalogue catalogue(
      JdbcTemplate jdbc, UnitOfWork work, InformationAccess access, Clock clock) {
    var readable = new AtomicReference<InformationReadAccess>();
    var logs =
        new InformationJobs(
            new JdbcJobInformationRepository(jdbc),
            access,
            (context, revision) -> readable.get().requireReadable(context, revision));
    var catalogue =
        new InformationCatalogue(
            new JdbcInformationCatalogueRepository(jdbc, clock), work, access, logs);
    readable.set(catalogue);
    return catalogue;
  }

  /** Retains the former assertion shape while tests exercise real immutable DTO constructors. */
  public static java.util.Map<String, Object> view(Object value) {
    if (!value.getClass().isRecord())
      throw new IllegalArgumentException("expected catalogue view record");
    var result = new java.util.LinkedHashMap<String, Object>();
    for (var field : value.getClass().getRecordComponents()) {
      try {
        var property =
            field.getAccessor().getAnnotation(com.fasterxml.jackson.annotation.JsonProperty.class);
        String name =
            property == null || property.value().isEmpty() ? field.getName() : property.value();
        Object item = field.getAccessor().invoke(value);
        if (item != null) result.put(name, viewValue(item));
      } catch (ReflectiveOperationException invalid) {
        throw new AssertionError(invalid);
      }
    }
    return result;
  }

  public static java.util.List<java.util.Map<String, Object>> views(java.util.List<?> values) {
    return values.stream().map(InformationFixtures::view).toList();
  }

  private static Object viewValue(Object value) {
    if (value.getClass().isRecord()) return view(value);
    if (value instanceof java.util.List<?> list)
      return list.stream().map(InformationFixtures::viewValue).toList();
    if (value instanceof java.util.Map<?, ?> map) {
      var copy = new java.util.LinkedHashMap<Object, Object>();
      map.forEach((key, item) -> copy.put(key, viewValue(item)));
      return copy;
    }
    return value;
  }
}
