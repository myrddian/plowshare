package io.aeyer.plowshare.server.events;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.ScheduledWork;
import io.aeyer.plowshare.server.faults.CallerFault;
import org.junit.jupiter.api.Test;

class ScheduleDefinitionCodecTest {
  static ScheduledWork definition(String kind, String name, String mode) {
    return new ScheduledWork(
        1,
        "0 0 9 * * MON-FRI",
        "Australia/Melbourne",
        false,
        new ScheduledWork.Action(
            kind, "worker", name, "Review the project\nKeep exact input", mode),
        new ScheduledWork.Target("mailbox", null, null, null, null),
        new ScheduledWork.Limits(30, 8, 1));
  }

  @Test
  void roundTripsSkillsAndKeepsTheirArguments() {
    var definition = definition("skill", "review", "NEW");
    assertEquals(
        definition, ScheduleDefinitionCodec.read(ScheduleDefinitionCodec.write(definition)));
    assertEquals(
        "/skill:review --mode=NEW Review the project\nKeep exact input",
        definition.action().utterance());
  }

  @Test
  void rejectsUnknownFieldsAndScalarCoercion() {
    String valid = ScheduleDefinitionCodec.write(definition("agent", null, null));
    assertThrows(
        CallerFault.class,
        () ->
            ScheduleDefinitionCodec.read(
                valid.replace("\"version\" : 1", "\"version\" : 1, \"owner\" : \"other\"")));
    assertThrows(
        CallerFault.class,
        () ->
            ScheduleDefinitionCodec.read(
                valid.replace("\"zone\" : \"Australia/Melbourne\"", "\"zone\" : 123")));
    assertThrows(
        CallerFault.class,
        () ->
            ScheduleDefinitionCodec.read(
                valid.replace("\"paused\" : false", "\"paused\" : \"false\"")));
    assertThrows(
        CallerFault.class,
        () -> ScheduleDefinitionCodec.read(valid.replace("\"paused\" : false,", "")));
  }

  @Test
  void rejectsModesOnOrchestrationsAndConflictingDestinationFields() {
    assertThrows(
        IllegalArgumentException.class, () -> definition("orchestration", "research", "NEW"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ScheduledWork.Target("message", "other", null, "worker", "review"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ScheduledWork.Save(
                "../escape", "p", "server", definition("agent", null, null), false));
  }
}
