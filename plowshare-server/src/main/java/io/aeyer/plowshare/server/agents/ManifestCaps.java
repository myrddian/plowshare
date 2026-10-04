package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.protocol.EnvironmentFile;
import java.util.Map;

/** Execution caps in version 1 project files; other application fields remain opaque. */
final class ManifestCaps {
  private static final Map<String, String> KEYS =
      Map.of(
          "steps",
          "steps",
          "budget",
          "budget",
          "autoContinue",
          "auto-continue",
          "time",
          "time",
          "failedChecks",
          "failed-checks",
          "autoIncrease",
          "auto-increase");

  private ManifestCaps() {}

  static ProjectCaps parse(com.fasterxml.jackson.databind.JsonNode manifest, String source) {
    var caps = manifest.get("caps");
    if (caps == null) return ProjectCaps.NONE;
    if (!caps.isObject()) throw new IllegalArgumentException("Project caps must be an object");
    StringBuilder yaml = new StringBuilder("caps:\n");
    var fields = caps.fields();
    while (fields.hasNext()) {
      var field = fields.next();
      String key = KEYS.get(field.getKey());
      if (key == null) throw new IllegalArgumentException("Unknown project cap: " + field.getKey());
      var value = field.getValue();
      if (key.equals("auto-increase")
          ? !value.isBoolean()
          : !value.isIntegralNumber() || !value.canConvertToInt())
        throw new IllegalArgumentException("Invalid project cap: " + field.getKey());
      yaml.append("  ").append(key).append(": ").append(value).append('\n');
    }
    var parsed = EnvironmentFile.parse(yaml.toString()).caps();
    return new ProjectCaps(
        setting(parsed.steps(), source),
        setting(parsed.budget(), source),
        setting(parsed.autoContinue(), source),
        setting(parsed.time(), source),
        parsed.failedChecks() == null
            ? ProjectCaps.FAILED_CHECKS_DEFAULT
            : setting(parsed.failedChecks(), source),
        parsed.autoIncrease() == null
            ? ProjectCaps.BooleanSetting.UNSET
            : new ProjectCaps.BooleanSetting(parsed.autoIncrease(), source),
        null);
  }

  private static ProjectCaps.Setting setting(Integer value, String source) {
    return value == null ? ProjectCaps.Setting.UNSET : new ProjectCaps.Setting(value, source);
  }
}
