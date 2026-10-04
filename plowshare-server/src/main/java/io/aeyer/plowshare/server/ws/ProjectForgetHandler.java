package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.archive.ProjectStore;
import java.util.Map;
import java.util.Objects;

/**
 * {@code project.forget} — drop a project's workspace. The frame equivalent of {@code DELETE
 * /v1/projects/&#123;name&#125;}.
 *
 * <h2>Its memories are untouched, and nothing on this surface says so</h2>
 *
 * <p>There is no foreign key from {@code memories} to {@code projects}: a project that loses its
 * workspace keeps everything it has ever remembered, and its jobs go back to having no local file
 * access. <b>Saying that in words is the client tool's job</b> and not this handler's — {@code
 * ProjectTools} is where the sentence a person reads lives, because only a person reads it — and an
 * {@link Code#NO_CONTENT} answer carrying no body is what keeps this surface from implying a delete
 * it did not do.
 *
 * <p>The lent directories go with it. {@link ProjectStore#forget} argues that: a stale exclusion
 * left behind would fence off more than an operator meant, while a stale lent root would
 * <em>reach</em> more — handed to whoever defines the name next, without either of them ever being
 * told it was there.
 *
 * <h2>A project that had no workspace is a refusal and not silence</h2>
 *
 * <p>{@link ProjectStore#forget} refuses it so that a mistyped name cannot read as a workspace
 * successfully removed, which is the one outcome an operator would not go back and check. The
 * exception travels to {@code Faults} untouched; nothing here catches it, and a handler that had
 * swallowed it into a 204 would have made this surface's silence mean two different things.
 */
public final class ProjectForgetHandler implements FrameHandler {

  private final ProjectStore projects;

  /**
   * @param projects the one store both surfaces forget through
   */
  public ProjectForgetHandler(ProjectStore projects) {
    this.projects = Objects.requireNonNull(projects, "projects");
  }

  @Override
  public Outcome handle(Map<String, Object> payload, Asking asking) {
    projects.forget(
        Payloads.required(
            payload,
            "project",
            FrameTypes.PROJECT_FORGET,
            "the name project.list answers with. Nothing was dropped."));
    return new Outcome(Code.NO_CONTENT, null, null);
  }
}
