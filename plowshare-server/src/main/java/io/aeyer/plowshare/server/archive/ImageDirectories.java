package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.data.DataLayout;
import java.nio.file.Path;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Which directory a project's images live in, given a {@link Home}.
 *
 * <p>{@link ExportDirectories}' twin, and it is in this package for that
 * interface's reason rather than because images are archive: a directory is
 * named by a project's <b>id</b> and everything above the archive knows a
 * project by its <b>name</b>, so somebody has to translate, and {@code
 * ProjectIds} — which is package-private, deliberately, so that the translation
 * happens in exactly one place — is here. Handing {@code ImageStore} a {@code
 * JdbcTemplate} so it could resolve a name itself would make every test of the
 * file layout need a database, which is the trade {@link ExportDirectories}
 * already refused once.
 *
 * <p><b>{@code ProjectIds.toWrite} and not {@code forDirectory}, and that is the
 * one place this differs from exports.</b> An export comes out of a
 * conversation, and a conversation's project is a foreign key — so a name with
 * no row is a state the archive cannot reach by writing, and {@code
 * forDirectory} rightly raises. An image arrives from outside with a project
 * name a person typed, and a project that nothing has written to yet is the
 * ordinary shape of a project rather than a mistake: {@code V3}'s "a project may
 * hold memories with no workspace" applies just as much to one that holds only
 * an image. So the upload registers the project, exactly as writing a memory to
 * it would, and the row and the directory come into being together.
 */
@FunctionalInterface
public interface ImageDirectories {

    /**
     * Where this tier's images go.
     *
     * @param home whose images — a project, or the global tier
     * @return an absolute directory, which need not exist yet. {@code
     *     ImageStore} creates it on the first write, on the standing rule that a
     *     server nobody has uploaded to should not leave an empty directory
     *     behind to explain
     */
    Path forProject(Home home);

    /**
     * The binding for a deployment that keeps a data directory.
     *
     * <p>Here rather than inlined at the bean method so a test can use the
     * binding rather than a copy of it — {@link ExportDirectories#under} makes
     * that argument at length, and a directory scheme is exactly the kind of
     * thing that gets edited in one place.
     */
    static ImageDirectories under(DataLayout data, JdbcTemplate jdbc) {
        return home -> data.imagesFor(ProjectIds.toWrite(jdbc, home));
    }
}
