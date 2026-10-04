package io.aeyer.plowshare.server.ws;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The routing table: every frame type this server answers, assembled from the {@link FrameArea}s
 * that each name their own.
 *
 * <h2>What a breadth task touches to add a frame type</h2>
 *
 * <p>A constant in {@link FrameTypes}, a {@link FrameHandler} beside {@link ProjectDefineHandler},
 * one entry in <b>its own area's</b> {@code FrameArea#frames()} map — a new {@code *Frames.java} if
 * that controller has none yet — a declaration in {@code client.Capabilities}, and a parity test.
 * <b>Nothing in this file.</b> That is the whole of the change from the shape the two pilots landed
 * with, and the reason for it is in {@link FrameArea}'s javadoc: seven parallel tasks editing one
 * {@code @Bean} signature is seven conflicts and a broken call site in every parity test written
 * before them.
 *
 * <h2>Still literal, still readable without running anything</h2>
 *
 * <p>The mapping from type to handler is written out in source, per area — see {@link FrameArea}.
 * This class only unions those maps, and the union is checked rather than assumed: <b>two areas
 * claiming one type fails the boot</b>, naming the type, which is the failure two parallel tasks
 * are most likely to produce (the same endpoint given a frame by two different tasks, each green in
 * its own branch).
 *
 * <h2>Why this is not in {@link EventChannelConfig}, which is where the router bean used to live
 * </h2>
 *
 * <p>Because a handler needs the services the rest of the application is built from — every one of
 * which needs a {@code JdbcTemplate} and therefore a database — while {@link EventChannelConfig} is
 * imported by five test contexts that stand up the web layer and nothing else. A router bean
 * assembled there could no longer be built in any of them, and the channel would become untestable
 * without Postgres to hold a table it does not read. Splitting the table out is what keeps those
 * contexts buildable: they get no {@code FrameArea} beans and no router at all, so {@link
 * EventChannelConfig} falls back to a router that claims nothing, and {@code EventChannelTest} goes
 * on measuring the path between a socket and an {@code Outcome} rather than a pilot's answer.
 *
 * <p><b>Found by component scan</b>, on {@link EventChannelConfig}'s own terms:
 * {@code @SpringBootApplication} sits on {@code io.aeyer.plowshare.server} and this is a child
 * package. {@code FrameShapeTest} proves the half a breadth task can get wrong by hand — that every
 * {@code FrameArea} in this package is annotated to be found at all — and {@code BootWiringTest}
 * scans this package and {@code agents} for real, with no database, so a table this class assembles
 * into a wiring the container cannot build fails in the fast suite. That is not a hypothetical: the
 * first frame area to reach the agent services closed a cycle through {@code jobStore}, and only a
 * scanned context could see it.
 */
@Configuration
public class FrameRoutingConfig {

  /**
   * The one router this application routes frames through.
   *
   * @param areas every {@link FrameArea} bean Spring found — the seven or so files the breadth plan
   *     will have added by the time it is done, in no particular order, since a routing table is a
   *     map and not a list
   * @throws IllegalStateException if two areas claim the same frame type
   * @throws IllegalArgumentException if any key is not a dotted discriminator, from {@link
   *     FrameRouter}'s own constructor
   */
  @Bean
  public FrameRouter frameRouter(List<FrameArea> areas) {
    Map<String, FrameHandler> table = new HashMap<>();
    for (FrameArea area : areas) {
      for (Map.Entry<String, FrameHandler> entry : area.frames().entrySet()) {
        FrameHandler already = table.put(entry.getKey(), entry.getValue());
        if (already != null) {
          throw new IllegalStateException(
              "two frame areas both claim \""
                  + entry.getKey()
                  + "\": "
                  + already.getClass().getName()
                  + " and "
                  + entry.getValue().getClass().getName()
                  + ". One endpoint gets one"
                  + " frame type, and which of the two answered would otherwise"
                  + " depend on bean ordering.");
        }
      }
    }
    return new FrameRouter(table);
  }
}
