package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.data.DataLayout;
import java.nio.file.Path;

/**
 * Which directory an ejected payload belongs in, given whose conversation it came out of.
 *
 * <h2>Why {@link PayloadExport} does not simply hold a root</h2>
 *
 * <p>It held one until the data directory arrived, and the tree was {@code <root>/<root
 * conversation id>/…} — flat, because nothing about an export knew a project. That was not a
 * decision; it was the only thing available. The consequence was that "what is in here, and whose
 * is it" could only be answered by reading every manifest in the directory, and "this project is
 * gone, what of it is still on disk" could not be answered at all.
 *
 * <p>Under {@code DataLayout} an export is {@code projects/<project-id>/exports/<root conversation
 * id>/…}, so the question has a directory-shaped answer. Getting there means an export must learn
 * its project, and a project is a <em>name</em> everywhere above the archive and an <em>id</em> in
 * it — so somebody has to translate, and that somebody owns a project repository. {@link
 * PayloadExport} does not, and should not: it is the class that turns bytes into files, driven from
 * inside a retention sweep, and giving it a database connection so it can resolve a name would make
 * every test of the file format need one.
 *
 * <p>So the translation is a seam, bound once in {@code ArchiveConfig} where the two configurations
 * that answer it differently — the data directory's own place, and an operator's {@code
 * plowshare.conversations.retention.export-directory} — are already read.
 *
 * <p><b>It takes a {@link Home} and not an id</b>, because {@code Home} is what a {@code
 * ConversationRecord} carries and because the global tier has no id: it is "the absence of a
 * project rather than a project named 'global'", and an interface taking {@code Long} would have to
 * say that by passing null, which is the reading {@code ProjectIds} exists to stop being invented
 * at nine call sites.
 */
@FunctionalInterface
public interface ExportDirectories {

  /**
   * Where this conversation's tree of ejected payloads goes.
   *
   * @param home whose conversation it was — a project, or the global tier
   * @return an absolute directory, which need not exist yet. {@code PayloadExport.write} creates
   *     what it needs when it needs it, on the rule that a server which never ejects anything
   *     should not leave an empty directory behind to explain
   * @throws ArchiveException if the home names a project this server has no row for. <b>Raised
   *     rather than answered with a stand-in</b>: the stand-in would be {@code ProjectIds.NONE},
   *     which is 0, and a {@code projects/0/} directory would collect the exports of every project
   *     that could not be resolved into one place named after none of them
   */
  Path forProject(Home home);

  /**
   * The binding for a deployment that named no export directory of its own: the data directory's
   * place for them.
   *
   * <p><b>Here and not inlined at the one bean method, so that a test can use the binding rather
   * than a copy of it.</b> {@code RetentionTest} drives a real sweep against a real database and
   * asserts where the file lands; if it built its own lambda of the same shape there would be two
   * schemes, and a change to the one in the wiring would leave the one with the test green. That is
   * the failure this whole file is against — a directory scheme is exactly the kind of thing that
   * gets edited in one place.
   */
  static ExportDirectories under(DataLayout data, ProjectDirectories projects) {
    return home -> data.exportsFor(projects.existing(home));
  }

  /**
   * The binding for a deployment that named one.
   *
   * <p><b>The same shape of answer</b>, {@code <root>/<project-id>/}, which {@code
   * DataLayout.exportsUnder} argues at length: the override used to mean "write the trees flat in
   * here", and keeping that would make "which project is this export of" answerable on one
   * deployment and not on another.
   */
  static ExportDirectories into(Path named, ProjectDirectories projects) {
    return home -> DataLayout.exportsUnder(named, projects.existing(home));
  }
}
