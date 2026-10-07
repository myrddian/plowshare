package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;

import io.aeyer.plowshare.server.PlowshareServerApplication;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

/**
 * Every {@link FrameArea} on the class path, found the way Spring finds them and built over mocks.
 *
 * <h2>Why the areas are discovered rather than listed</h2>
 *
 * <p>A list of areas written out in a test is a second routing table, and it has the defect {@code
 * ParityTest}'s own javadoc names: it "would pass on the day a tool was deleted", and here it would
 * also be a line seven parallel breadth tasks all have to edit — the very shape splitting the table
 * per area was meant to remove. Scanning by <em>type</em> rather than by annotation is what lets
 * {@code FrameShapeTest} fail an area that forgot {@code @Component} and would therefore never have
 * been collected by Spring at all.
 *
 * <p><b>Constructed over Mockito mocks</b>, because what is under test here is the table and not
 * what any handler does with a store: an area's constructor takes the services its controller
 * takes, every one of which needs a database behind it in production. A mock answers the shape
 * without one.
 */
final class FrameAreas {

  private FrameAreas() {}

  /**
   * Every concrete {@link FrameArea} under {@link PlowshareServerApplication}'s own base package,
   * by class — not just this one: {@code union.UnionFrames} is the first area to live outside
   * {@code ws}, in the package {@code union.UnionStore} and its siblings are required to use, and
   * {@code @SpringBootApplication}'s own scan reaches it from the application's root package rather
   * than from {@link FrameArea}'s. Narrowing this scan to {@code FrameArea.class.getPackageName()}
   * would find every area this package holds and silently miss any area a sibling package adds —
   * exactly the gap a {@code FrameArea} outside {@code ws} would fall into.
   */
  static List<Class<?>> declared() {
    ClassPathScanningCandidateComponentProvider scan =
        new ClassPathScanningCandidateComponentProvider(false);
    scan.addIncludeFilter(new AssignableTypeFilter(FrameArea.class));
    List<Class<?>> found = new ArrayList<>();
    for (BeanDefinition candidate :
        scan.findCandidateComponents(PlowshareServerApplication.class.getPackageName())) {
      try {
        found.add(Class.forName(candidate.getBeanClassName()));
      } catch (ClassNotFoundException impossible) {
        throw new IllegalStateException(
            "the scan named a class it cannot load: " + candidate, impossible);
      }
    }
    assertFalse(
        found.isEmpty(),
        "the scan found no FrameArea at all, which means it is measuring nothing --"
            + " every assertion built on it would pass vacuously");
    return found;
  }

  /**
   * Every area, instantiated over mocked services.
   *
   * <p><b>A primitive constructor parameter is not a service to mock</b> — {@code UnionFrames}' own
   * {@code maxFileBytes}, a plain {@code @Value}-read number and the one constructor argument on
   * this whole surface that is a configured value rather than a collaborator. Mockito refuses to
   * mock a primitive type outright, so that argument is filled with its type's zero value instead,
   * the same way an unset field would read; nothing here reads it back, since what is under test is
   * the routing table and not any area's arithmetic over a byte limit.
   *
   * @throws IllegalStateException if an area cannot be built that way, which is a finding rather
   *     than a flake: an area whose constructor takes something unmockable and non-primitive has
   *     taken a value rather than a service, and the services are what make a frame handler call
   *     what its controller calls
   */
  static List<FrameArea> mocked() {
    List<FrameArea> areas = new ArrayList<>();
    for (Class<?> type : declared()) {
      Constructor<?> only = productionConstructor(type);
      Object[] services = new Object[only.getParameterCount()];
      for (int at = 0; at < services.length; at++) {
        Class<?> parameter = only.getParameterTypes()[at];
        services[at] =
            parameter.isPrimitive()
                ? Array.get(Array.newInstance(parameter, 1), 0)
                : parameter == String.class
                    ? java.nio.file.Path.of(System.getProperty("java.io.tmpdir"))
                        .toAbsolutePath()
                        .toString()
                    : mock(parameter);
      }
      try {
        areas.add((FrameArea) only.newInstance(services));
      } catch (ReflectiveOperationException notBuildable) {
        throw new IllegalStateException(
            type.getName() + " could not be built over mocks of its own constructor's services",
            notBuildable);
      }
    }
    return areas;
  }

  /**
   * Every service some area's constructor asks for, once each — what a container has to be able to
   * hand them.
   *
   * <p>A primitive parameter is excluded, on {@link #mocked()}'s own reasoning: {@code
   * UnionFrames}' {@code maxFileBytes} is a {@code @Value}-resolved number and not a collaborator,
   * so it is not a bean any container "hands" anything — Spring's placeholder resolution supplies
   * it directly from the {@code @Value} annotation's own default, with no bean definition involved
   * at all. Registering one here would ask {@link #mocked()}'s caller to stand up a bean named
   * {@code "long"}, which is exactly the failure a primitive constructor argument is not: nothing
   * needs it, because nothing but this area's own {@code @Value} reads it.
   */
  static List<Class<?>> servicesNeeded() {
    List<Class<?>> needed = new ArrayList<>();
    for (Class<?> type : declared()) {
      for (Class<?> service : productionConstructor(type).getParameterTypes()) {
        if (!service.isPrimitive() && service != String.class && !needed.contains(service)) {
          needed.add(service);
        }
      }
    }
    // Policy bindings use required setter injection to avoid constructor churn across
    // legacy adapters. The container must still provide each of those collaborators.
    for (Class<?> type : declared()) {
      for (var method : type.getDeclaredMethods()) {
        if (method.isAnnotationPresent(
            org.springframework.beans.factory.annotation.Autowired.class)) {
          for (Class<?> service : method.getParameterTypes()) {
            if (!service.isPrimitive() && !needed.contains(service)) needed.add(service);
          }
        }
      }
    }
    // A concrete repository mock also supplies its capability interfaces. Registering a second
    // mock for an interface would create an ambiguity that production's single bean does not have.
    return needed.stream()
        .filter(
            service ->
                needed.stream()
                    .noneMatch(other -> other != service && service.isAssignableFrom(other)))
        .toList();
  }

  /** Compatibility constructors are not Spring's production composition contract. */
  private static Constructor<?> productionConstructor(Class<?> type) {
    return java.util.Arrays.stream(type.getDeclaredConstructors())
        .filter(
            constructor ->
                constructor.isAnnotationPresent(
                    org.springframework.beans.factory.annotation.Autowired.class))
        .findFirst()
        .orElseGet(() -> type.getDeclaredConstructors()[0]);
  }

  /**
   * The production routing table, merged the way {@link FrameRoutingConfig} merges it at boot — the
   * real table and not a second list of it.
   */
  static FrameRouter router() {
    return new FrameRoutingConfig().frameRouter(mocked());
  }
}
