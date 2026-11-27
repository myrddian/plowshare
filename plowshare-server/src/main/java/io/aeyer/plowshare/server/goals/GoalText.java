package io.aeyer.plowshare.server.goals;

import io.aeyer.plowshare.server.auth.Tokens;
import io.aeyer.plowshare.server.faults.CallerFault;

final class GoalText {
  private GoalText() {}

  static String digest(String... fields) {
    var framed = new StringBuilder();
    for (String value : fields) {
      if (value == null) framed.append("-1:");
      else framed.append(value.length()).append(':').append(value);
    }
    return Tokens.hash(framed.toString());
  }

  static String require(String value, String name, int limit) {
    if (value == null || value.isBlank() || value.length() > limit || value.indexOf(0) >= 0)
      throw new CallerFault(name + " must be nonblank text of at most " + limit + " characters");
    return value;
  }
}
