package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.hooks.script.HookEngine;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;

/**
 * Fresh deny-all contexts with a two-second deadline covering module initialization and routing.
 */
public final class GraalRelayRouteProgram implements RelayRouteProgram, AutoCloseable {
  private final HookEngine engine = new HookEngine();

  @Override
  public RelayRouting.Manifest manifest(RelayDeliveries.SourcePin routing) {
    return RelayRouteCodec.manifest(evaluate(routing, null));
  }

  @Override
  public List<RelayRouting.Selection> route(
      RelayRouting.Package relay, RelayRouting.Subscription subscription, Relay.Publication input) {
    if (!relay.manifest().subscriptions().contains(subscription)
        || !subscription.topic().equals(input.topic().name())
        || subscription.kind() != input.event().payload().kind())
      throw new IllegalArgumentException("Relay input does not match its declared subscription");
    return RelayRouteCodec.selections(
        evaluate(relay.routing(), RelayRouteCodec.input(subscription, input)));
  }

  private String evaluate(RelayDeliveries.SourcePin routing, String input) {
    try (Context context = engine.newContext();
        var timer = Executors.newSingleThreadScheduledExecutor()) {
      var deadline = timer.schedule(() -> context.close(true), 2, TimeUnit.SECONDS);
      try {
        // Capture serialization before project code can replace guest builtins. Freeze recursively
        // so routes cannot mutate their input, and never supply host objects to the guest.
        var adapter =
            context.eval(
                "js",
                """
            (() => {
              const stringify = JSON.stringify, parse = JSON.parse;
              const freeze = Object.freeze, values = Object.values;
              const immutable = value => {
                if (value !== null && typeof value === 'object') {
                  for (const child of values(value)) immutable(child);
                  freeze(value);
                }
                return value;
              };
                for (const name of ['Date', 'performance', 'Temporal', 'Intl'])
                Object.defineProperty(globalThis, name, {value: undefined, configurable: false, writable: false});
              Object.defineProperty(Math, 'random', {value: () => {throw new Error('random unavailable')}, configurable: false, writable: false});
              return (module, encoded) => stringify(encoded === null
                ? module.manifest : module.route(immutable(parse(encoded))));
            })()
            """);
        var module =
            context.eval(
                Source.newBuilder("js", routing.source(), "relay.mjs")
                    .mimeType("application/javascript+module")
                    .buildLiteral());
        if (!module.hasMember("manifest")
            || !module.hasMember("route")
            || !module.getMember("route").canExecute())
          throw new CallerFault("Relay routes.js must export manifest and route(event)");
        var result = adapter.execute(module, input);
        if (!result.isString() || result.asString().length() > 65536)
          throw new CallerFault("Relay route output must be bounded JSON");
        return result.asString();
      } finally {
        deadline.cancel(false);
      }
    } catch (Exception failed) {
      // Guest errors and parser causes can contain private event/source excerpts. Do not propagate
      // them to server logs or callers; admission never starts after a failed evaluation.
      throw new CallerFault(
          "Relay routes.js failed: check exports, sandbox restrictions, output and execution limit");
    }
  }

  @Override
  public void close() {
    engine.close();
  }
}
