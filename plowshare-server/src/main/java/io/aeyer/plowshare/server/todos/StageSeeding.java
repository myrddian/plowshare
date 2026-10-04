package io.aeyer.plowshare.server.todos;

import java.util.List;

/** The harness writing an orchestration's stages into a new conversation's empty list. */
public interface StageSeeding {

  /** One stage: its id in the definition, and the text the list shows. */
  record Seed(String stageId, String text) {}

  /**
   * @throws IllegalStateException if the list is not empty, or a seed is blank or repeated
   */
  List<TodoItem> seedStages(String conversation, List<Seed> stages);
}
