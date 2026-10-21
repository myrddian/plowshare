package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Prepared command arguments are validated values before authorization, gating or reservation. */
sealed interface InformationCommandParameters {
  record None() implements InformationCommandParameters {}

  record Link(String project, boolean remove) implements InformationCommandParameters {
    public Link {
      if (project == null
          || project.isBlank()
          || project.length() > 1024
          || project.codePoints().anyMatch(Character::isISOControl))
        throw new CallerFault("link requires a bounded project identity");
      project = project.strip();
    }
  }

  record Sharing(boolean shared) implements InformationCommandParameters {}

  record Availability(String state) implements InformationCommandParameters {
    public Availability {
      if (!Set.of("active", "excluded", "included", "withdrawn", "deleted").contains(state))
        throw new CallerFault("unknown availability");
    }
  }

  record Processing(String from, List<String> stages) implements InformationCommandParameters {
    public Processing {
      stages = List.copyOf(stages);
      if (stages.isEmpty()
          || stages.size() > 7
          || stages.stream().distinct().count() != stages.size()
          || stages.stream().anyMatch(stage -> !InformationCatalogue.STAGES.contains(stage)))
        throw new CallerFault("invalid selected processing stages");
    }
  }

  record Rebuild(String stage) implements InformationCommandParameters {
    public Rebuild {
      if (!Set.of("embed", "summarise", "summary_embed", "autoTag", "tagGroups").contains(stage))
        throw new CallerFault("rebuild must name a downstream projection stage");
    }
  }

  record Allowance(int total) implements InformationCommandParameters {
    public Allowance {
      if (total < 1) throw new CallerFault("processing allowance must be positive");
    }
  }

  record Tags(List<String> values) implements InformationCommandParameters {
    public Tags {
      values = InformationFacets.tags(values);
    }
  }

  record TagsReplace(List<String> values, long expectedVersion)
      implements InformationCommandParameters {
    public TagsReplace {
      values = InformationFacets.tags(values);
      if (expectedVersion < 0 || expectedVersion > 9007199254740991L)
        throw new CallerFault("invalid expected tag version");
    }
  }

  record Groups(Map<String, List<String>> values) implements InformationCommandParameters {
    public Groups {
      if (values != null) values = InformationTagGroups.from(values, null);
    }
  }
}
