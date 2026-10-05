package io.aeyer.plowshare.server.relay;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.server.faults.CallerFault;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class RelayRouteProgramTest {
  static final String DECLARATION =
      """
      export const manifest = {version: 1, subscriptions: [
        {name: 'due', topic: 'schedule.due', kind: 'schedule.due', start: 'oldest-retained'}
      ]};
      """;
  static final Relay.Publication INPUT =
      new Relay.Publication(
          new Relay.TopicKey(9, "schedule.due"),
          9007199254740993L,
          Instant.EPOCH,
          new Relay.Draft(
              "event",
              "scheduler",
              Instant.EPOCH,
              null,
              null,
              new RelayPayload.ScheduleDue("daily", "digest", Instant.EPOCH)));

  @Test
  void routing_has_typed_fanout_optional_scripts_and_exact_positions() {
    try (var program = new GraalRelayRouteProgram()) {
      var relay =
          packageFor(
              program,
              DECLARATION
                  + """
          export function route(event) {
            if (event.position !== '9007199254740993' || event.subscription !== 'due'
                || event.payload.schedule !== 'daily') throw new Error('wrong input');
            return [{name:'notify', receiver:'agent-notices'},
                    {name:'review', receiver:'local-handler', script:'review.js'}];
          }
          """);
      var branches = program.route(relay, relay.manifest().subscriptions().getFirst(), INPUT);
      assertEquals(
          List.of(
              new RelayRouting.Selection("notify", "agent-notices", null),
              new RelayRouting.Selection("review", "local-handler", "review.js")),
          branches);
    }
  }

  @Test
  void zero_branches_and_fresh_contexts_are_valid() {
    try (var program = new GraalRelayRouteProgram()) {
      var relay =
          packageFor(
              program,
              DECLARATION
                  + """
          let calls = 0;
          export function route(event) { if (++calls !== 1) throw new Error('state leaked'); return []; }
          """);
      for (int i = 0; i < 2; i++)
        assertEquals(
            List.of(), program.route(relay, relay.manifest().subscriptions().getFirst(), INPUT));
    }
  }

  @Test
  void publication_selection_has_a_typed_pinned_target_and_rejects_cross_project_fields() {
    try (var program = new GraalRelayRouteProgram()) {
      var pin =
          RelayDeliveries.SourcePin.of(
              "notices/routes.js",
              """
          export const manifest={version:1,subscriptions:[{name:'release',topic:'release.observed',kind:'text',start:'latest'}]};
          export function route(){return [{name:'forward',receiver:'relay.publish',publishTo:'release.forwarded'}];}
          """);
      var relay = new RelayRouting.Package("notices", pin, program.manifest(pin));
      var sub = relay.manifest().subscriptions().getFirst();
      var input =
          new Relay.Publication(
              new Relay.TopicKey(9, sub.topic()),
              1,
              Instant.EPOCH,
              new Relay.Draft(
                  "release",
                  "publisher",
                  Instant.EPOCH,
                  null,
                  null,
                  new RelayPayload.Text("released")));
      assertEquals(
          List.of(
              new RelayRouting.Selection("forward", "relay.publish", null, "release.forwarded")),
          program.route(relay, sub, input));
      for (String result :
          List.of(
              "[{name:'n',receiver:'relay.publish'}]",
              "[{name:'n',receiver:'relay.publish',publishTo:'../escape'}]",
              "[{name:'n',receiver:'relay.publish',publishTo:null}]",
              "[{name:'n',receiver:'agent',publishTo:'release.forwarded'}]",
              "[{name:'n',receiver:'relay.publish',publishTo:'release.forwarded',projectId:10}]"))
        assertThrows(
            RuntimeException.class, () -> RelayRouteCodec.selections(result.replace("'", "\"")));
    }
  }

  @Test
  void malformed_unknown_and_oversized_guest_plans_fail() {
    try (var program = new GraalRelayRouteProgram()) {
      for (String result :
          List.of(
              "null",
              "{}",
              "Promise.resolve([])",
              "[{name:'n',receiver:'r',unexpected:true}]",
              "[{name:'n',receiver:1}]",
              "[{name:'n',receiver:'r',script:'../../escape.js'}]",
              "[{name:'n',receiver:'r',script:null}]",
              "[{name:'n',receiver:'r'},{name:'n',receiver:'r'}]",
              "Array.from({length:33},(_,i)=>({name:'n'+i,receiver:'r'}))")) {
        var relay =
            packageFor(program, DECLARATION + "export function route() {return " + result + ";}");
        assertThrows(
            RuntimeException.class,
            () -> program.route(relay, relay.manifest().subscriptions().getFirst(), INPUT),
            result);
      }
    }
  }

  @Test
  void guest_cannot_mutate_input_or_reach_host_io_clock_and_random() {
    try (var program = new GraalRelayRouteProgram()) {
      for (String forbidden :
          List.of(
              "event.payload.schedule = 'changed'",
              "Java.type('java.lang.System')",
              "load('private.js')",
              "new Date()",
              "new Intl.DateTimeFormat().format()",
              "Math.random()",
              "fetch('https://example.invalid')")) {
        var relay =
            packageFor(
                program,
                DECLARATION + "export function route(event) {" + forbidden + ";return [];}");
        assertThrows(
            CallerFault.class,
            () -> program.route(relay, relay.manifest().subscriptions().getFirst(), INPUT),
            forbidden);
      }
      assertThrows(
          CallerFault.class,
          () ->
              program.manifest(
                  RelayDeliveries.SourcePin.of(
                      "notices/routes.js",
                      "import './private.js';"
                          + DECLARATION
                          + "export function route(){return [];}")));
    }
  }

  @Test
  void initialization_and_routing_timeouts_fail_without_private_exception_text() {
    try (var program = new GraalRelayRouteProgram()) {
      var error =
          assertThrows(
              CallerFault.class,
              () ->
                  program.manifest(
                      RelayDeliveries.SourcePin.of(
                          "notices/routes.js",
                          DECLARATION + "while(true) {} export function route(){return [];}")));
      assertNull(error.getCause());
      var relay = packageFor(program, DECLARATION + "export function route(){while(true) {}}");
      assertThrows(
          CallerFault.class,
          () -> program.route(relay, relay.manifest().subscriptions().getFirst(), INPUT));
      var privateRelay =
          packageFor(
              program, DECLARATION + "export function route(){throw new Error('private-secret');}");
      var privateError =
          assertThrows(
              CallerFault.class,
              () ->
                  program.route(
                      privateRelay, privateRelay.manifest().subscriptions().getFirst(), INPUT));
      assertFalse(privateError.getMessage().contains("private-secret"));
      assertNull(privateError.getCause());
    }
  }

  @Test
  void manifest_and_event_family_validation_precede_routing() {
    try (var program = new GraalRelayRouteProgram()) {
      for (String declaration :
          List.of(
              "export const manifest = {version:2,subscriptions:[]};",
              "export const manifest = {version:1,subscriptions:[{name:'n',topic:'schedule.due',kind:'text',start:'latest'}]};",
              "export const manifest = {version:1,subscriptions:[],unknown:true};"))
        assertThrows(
            RuntimeException.class,
            () -> packageFor(program, declaration + "export function route(){return [];}"));
      var relay = packageFor(program, DECLARATION + "export function route(){return [];}");
      var wrong =
          new Relay.Publication(
              INPUT.topic(),
              1,
              Instant.EPOCH,
              new Relay.Draft(
                  "wrong", "publisher", Instant.EPOCH, null, null, new RelayPayload.Empty()));
      assertThrows(
          IllegalArgumentException.class,
          () -> program.route(relay, relay.manifest().subscriptions().getFirst(), wrong));
    }
  }

  static RelayRouting.Package packageFor(RelayRouteProgram program, String source) {
    var pin = RelayDeliveries.SourcePin.of("notices/routes.js", source);
    return new RelayRouting.Package("notices", pin, program.manifest(pin));
  }
}
