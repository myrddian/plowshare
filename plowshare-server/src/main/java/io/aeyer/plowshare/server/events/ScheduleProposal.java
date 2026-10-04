package io.aeyer.plowshare.server.events;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * What a sentence was read as: a schedule and a trigger nobody has saved yet.
 *
 * <p><b>A proposal and not a definition.</b> {@code schedule.read} stores nothing; a person
 * confirms this and the client saves it through {@code schedule.define} and {@code trigger.define},
 * which check everything again. So every field is shaped to be sent back through those two frames
 * unchanged — {@code cron} and {@code zone} are {@code schedule.define}'s, {@code agent}, {@code
 * task}, {@code project} and {@code conversation} are {@code trigger.define}'s, and {@link Names}
 * is the three names that tie them together.
 *
 * <p>Three fields are never the model's: {@link #nextFires} is computed from {@link CronSchedule},
 * {@link #names} is generated in code, and the destination ({@link #intoConversation}, {@link
 * #project}, {@link #conversation}) is decided from what the request carried. See {@link
 * ScheduleReader} for why each.
 *
 * @param cron Spring's six fields, seconds first; already parsed once by this server
 * @param zone the IANA zone {@code cron} is read in, {@code "UTC"} when the request named none
 * @param when the schedule in words, for the person confirming it
 * @param agent the agent or bot the trigger runs; runnable from the destination's tier
 * @param task what that agent is told to do, in the imperative
 * @param intoConversation whether the result is a turn in {@link #conversation} rather than an item
 *     in the signed-in account's inbox
 * @param project the request's project when the result goes to the inbox, else null
 * @param conversation the request's conversation when {@link #intoConversation}, else null
 * @param nextFires the next three instants {@code cron} fires, from the moment it was read
 * @param names what the schedule, the trigger and the event it emits would be called
 */
public record ScheduleProposal(
    String cron,
    String zone,
    String when,
    String agent,
    String task,
    boolean intoConversation,
    String project,
    String conversation,
    List<Instant> nextFires,
    Names names) {

  public ScheduleProposal {
    Objects.requireNonNull(cron, "cron");
    Objects.requireNonNull(zone, "zone");
    Objects.requireNonNull(agent, "agent");
    Objects.requireNonNull(task, "task");
    nextFires = List.copyOf(nextFires);
    Objects.requireNonNull(names, "names");
  }

  /**
   * The schedule's name, the trigger's name, and the event that joins them — all one slug.
   *
   * <p>Three fields that are equal rather than one, because they are three names in two frames and
   * a client should not have to know they happen to coincide. One name for all three is what makes
   * a proposal's schedule and trigger findable as a pair in {@code schedule.list} and {@code
   * trigger.list} afterwards.
   */
  public record Names(String schedule, String trigger, String event) {}
}
