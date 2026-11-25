package io.aeyer.plowshare.server.personal;

import io.aeyer.plowshare.protocol.RelayCausation;
import java.util.List;
import java.util.Optional;

/** The committed notification owed by one ordinary scheduled Mailbox result. */
interface PersonalScheduleAlertStore {
  record Alert(String item, String owner, String firing, String route, RelayCausation cause) {}

  List<String> due(int limit);

  /** Hold the one item claim until the messaging acceptance transaction commits. */
  Optional<Alert> lock(String item);

  void sent(String item, String message);

  void retry(String item);

  void refuse(String item);
}
