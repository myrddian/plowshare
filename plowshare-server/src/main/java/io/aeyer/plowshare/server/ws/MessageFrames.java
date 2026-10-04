package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.agents.CallerAccess;
import io.aeyer.plowshare.server.board.BoardMessaging;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Account and project scoped controls for the messaging abstraction. WS is the only front door. */
@Component
public class MessageFrames implements FrameArea {
  private final java.util.function.Supplier<BoardMessaging> transport;
  private final CallerAccess access;

  @org.springframework.beans.factory.annotation.Autowired
  public MessageFrames(
      org.springframework.beans.factory.ObjectProvider<BoardMessaging> provider,
      CallerAccess access) {
    this.transport = provider::getObject;
    this.access = access;
  }

  public MessageFrames(BoardMessaging messages, CallerAccess access) {
    this.transport = () -> messages;
    this.access = access;
  }

  private BoardMessaging messages() {
    return transport.get();
  }

  @Override
  public Map<String, FrameHandler> frames() {
    return Map.of(
        FrameTypes.MESSAGE_INSTANCES,
        this::instances,
        FrameTypes.MESSAGE_INSTANCE,
        this::instance,
        FrameTypes.MESSAGE_INSTANCE_OPEN,
        this::open,
        FrameTypes.MESSAGE_INSTANCE_DEFAULT,
        this::makeDefault,
        FrameTypes.MESSAGE_INSTANCE_STOP,
        (p, a) -> stop(p, a, false),
        FrameTypes.MESSAGE_INSTANCE_ARCHIVE,
        (p, a) -> stop(p, a, true),
        FrameTypes.MESSAGE_DELIVERIES,
        this::deliveries,
        FrameTypes.MESSAGE_DELIVERY,
        this::delivery,
        FrameTypes.MESSAGE_CANCEL,
        this::cancel);
  }

  record ListBody(String project, Boolean archived, Integer offset, Integer limit) {}

  record OpenBody(String project, String agent, Boolean makeDefault, String requestId) {}

  record IdBody(String instance) {}

  record DeliveryBody(String message) {}

  record DeliveriesBody(String instance, Integer offset, Integer limit) {}

  public record Instances(List<BoardMessaging.InstanceView> instances, boolean more, int offset) {}

  public record Deliveries(List<BoardMessaging.Delivery> deliveries, boolean more, int offset) {}

  private String account(Asking asking, String type) {
    String handle = asking.requireHandle(type);
    access.requireSession(asking.sessionId(), handle);
    return handle;
  }

  private String owned(Map<String, Object> payload, Asking asking, String type) {
    String account = account(asking, type);
    String instance = Payloads.required(payload, "instance", type, "the instance address");
    String project = messages().owned(instance, account).project();
    if (io.aeyer.plowshare.server.access.ProjectAuthorization.required(type)
        == io.aeyer.plowshare.server.archive.ProjectRole.VIEWER)
      access.requireProject(project, account);
    else access.requireWork(project, account);
    return account;
  }

  private String ownsMessage(Map<String, Object> payload, Asking asking, String type) {
    String account = account(asking, type);
    String message = Payloads.required(payload, "message", type, "the message ID");
    var route =
        messages()
            .route(message)
            .orElseThrow(() -> new CallerFault("No accessible message with that ID."));
    if (type.equals(FrameTypes.MESSAGE_CANCEL))
      access.requireWork(messages().owned(route.sender(), account).project(), account);
    else access.requireProject(messages().owned(route.sender(), account).project(), account);
    messages().owned(route.recipient(), account);
    return account;
  }

  private static int limit(Integer value) {
    int limit = value == null ? 100 : value;
    if (limit < 1 || limit > 200)
      throw new CallerFault("Message inspection takes a limit from 1 to 200.");
    return limit;
  }

  private static int offset(Integer value) {
    int offset = value == null ? 0 : value;
    if (offset < 0) throw new CallerFault("Message inspection takes a nonnegative offset.");
    return offset;
  }

  Outcome instances(Map<String, Object> payload, Asking asking) {
    String account = account(asking, FrameTypes.MESSAGE_INSTANCES);
    ListBody body = Payloads.as(payload, ListBody.class, FrameTypes.MESSAGE_INSTANCES);
    if (body.project() == null || body.project().isBlank())
      throw new CallerFault("message.instances needs a project.");
    access.requireProject(body.project(), account);
    int limit = limit(body.limit()), offset = offset(body.offset());
    var page =
        messages()
            .listingPage(
                account, body.project(), Boolean.TRUE.equals(body.archived()), offset, limit);
    return Outcome.ok(new Instances(page.instances(), page.more(), page.offset()));
  }

  Outcome instance(Map<String, Object> payload, Asking asking) {
    String account = owned(payload, asking, FrameTypes.MESSAGE_INSTANCE);
    return Outcome.ok(messages().inspect((String) payload.get("instance"), account));
  }

  Outcome open(Map<String, Object> payload, Asking asking) {
    String account = account(asking, FrameTypes.MESSAGE_INSTANCE_OPEN);
    OpenBody body = Payloads.as(payload, OpenBody.class, FrameTypes.MESSAGE_INSTANCE_OPEN);
    access.requireWork(body.project(), account);
    return Outcome.ok(
        messages()
            .open(
                account,
                body.project(),
                body.agent(),
                Boolean.TRUE.equals(body.makeDefault()),
                body.requestId()));
  }

  Outcome makeDefault(Map<String, Object> payload, Asking asking) {
    String account = owned(payload, asking, FrameTypes.MESSAGE_INSTANCE_DEFAULT);
    return Outcome.ok(messages().makeDefault((String) payload.get("instance"), account));
  }

  Outcome stop(Map<String, Object> payload, Asking asking, boolean archive) {
    String type = archive ? FrameTypes.MESSAGE_INSTANCE_ARCHIVE : FrameTypes.MESSAGE_INSTANCE_STOP;
    String account = owned(payload, asking, type);
    return Outcome.ok(messages().stop((String) payload.get("instance"), account, archive));
  }

  Outcome deliveries(Map<String, Object> payload, Asking asking) {
    String account = owned(payload, asking, FrameTypes.MESSAGE_DELIVERIES);
    DeliveriesBody body = Payloads.as(payload, DeliveriesBody.class, FrameTypes.MESSAGE_DELIVERIES);
    int limit = limit(body.limit()), offset = offset(body.offset());
    var rows = messages().deliveries(body.instance(), account, offset, limit + 1);
    return Outcome.ok(
        new Deliveries(rows.stream().limit(limit).toList(), rows.size() > limit, offset));
  }

  Outcome delivery(Map<String, Object> payload, Asking asking) {
    String account = ownsMessage(payload, asking, FrameTypes.MESSAGE_DELIVERY);
    return Outcome.ok(messages().delivery((String) payload.get("message"), account));
  }

  Outcome cancel(Map<String, Object> payload, Asking asking) {
    String account = ownsMessage(payload, asking, FrameTypes.MESSAGE_CANCEL);
    return Outcome.ok(messages().cancel((String) payload.get("message"), account));
  }
}
