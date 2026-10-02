package io.aeyer.plowshare.server.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.client.files.ClientEnforcer;
import io.aeyer.plowshare.client.files.Workspace;
import io.aeyer.plowshare.protocol.FileReply;
import io.aeyer.plowshare.protocol.FileRequest;
import io.aeyer.plowshare.protocol.Needle;
import io.aeyer.plowshare.protocol.Span;
import io.aeyer.plowshare.protocol.Window;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A read, a stat, a glob and a search of a client's files, refused as facts
 * across the wire and worded here — {@link ClientChangeWordsTest}'s path for the
 * ops that do not change a file (spec 2026-09-30, step 2).
 *
 * <p>The Java client is real, the disk is real, and the socket is a JSON round
 * trip. Each expected sentence is the one the client itself wrote before it
 * reported facts, byte for byte, except where the step chose one wording for
 * two (a directory is said to be one) and where a fact was new (a hidden path
 * is said to be hidden, not outside).
 */
class ClientReadWordsTest {

    private static final ObjectMapper WIRE =
            new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private static final Window FIRST = Window.of(0, Window.MAX_WINDOW_LINES);

    @TempDir
    Path tmp;

    private Path repo;
    private Path other;
    private RemoteProvider provider;

    @BeforeEach
    void aClientOverOneTree() throws IOException {
        repo = Files.createDirectory(tmp.resolve("repo")).toRealPath();
        other = Files.createDirectory(tmp.resolve("other")).toRealPath();
        Workspace workspace = new Workspace();
        workspace.set(List.of(repo));
        ClientEnforcer client = new ClientEnforcer(workspace,
                (filename, bytes) -> "img_0000000000000042");
        provider = new RemoteProvider((session, request) -> {
            try {
                FileReply answered = client.answer(WIRE.readValue(
                        WIRE.writeValueAsString(request), FileRequest.class));
                return WIRE.readValue(WIRE.writeValueAsString(answered), FileReply.class);
            } catch (IOException broken) {
                throw new UncheckedIOException(broken);
            }
        }, "session-1", List.of(new Grant(Scope.WORKSPACE, Mode.READ)));
    }

    private String refused(Runnable asked) {
        return assertThrows(FileRefusedException.class, asked::run).getMessage();
    }

    @Test
    void a_read_the_client_refuses_reads_as_it_always_did() throws IOException {
        Files.write(repo.resolve("blob.bin"), new byte[] {(byte) 0xC3, 0x28});
        Path gone = repo.resolve("nope.txt");

        assertEquals("there is no file at " + gone + " on this machine",
                refused(() -> provider.read(gone, FIRST)));
        assertEquals("path " + repo.resolve("blob.bin") + " is not UTF-8 text; these tools read"
                + " text files only", refused(() -> provider.read(repo.resolve("blob.bin"),
                FIRST)));
        assertEquals("path " + other.resolve("x.txt") + " is outside this session's workspace,"
                + " which is " + repo + "; ask for the roots you have rather than guessing at"
                + " paths", refused(() -> provider.stat(other.resolve("x.txt"))));
        assertEquals(0, provider.oldClientReplies(), "every one of those came as facts");
    }

    @Test
    void a_directory_is_said_to_be_one_and_a_hidden_path_to_be_hidden() throws IOException {
        Path src = Files.createDirectory(repo.resolve("src"));
        Path secret = Files.writeString(repo.resolve(".env"), "TOKEN=notreal\n");

        assertEquals("path " + src + " is a directory on this machine, so there is nothing to"
                + " read", refused(() -> provider.read(src, FIRST)));
        assertEquals("path " + secret + " is inside this session's workspace, which is " + repo
                + ", but hidden: a name in it starts with '.', and no file tool reaches a hidden"
                + " path", refused(() -> provider.read(secret, FIRST)));
    }

    @Test
    void a_glob_and_a_search_the_client_refuses_read_as_they_always_did() {
        assertEquals("'/src/*.ts' is an absolute pattern, and a pattern is matched against paths"
                + " relative to a root; write 'src/*.ts' instead",
                refused(() -> provider.glob("/src/*.ts")));
        assertEquals("'**/**/**/**/**/x' has 5 '**/' segments and at most 4 are expanded; one is"
                + " almost always what is meant", refused(() -> provider.glob("**/**/**/**/**/x")));
        assertTrue(refused(() -> provider.glob("**/[unclosed.java"))
                .startsWith("'**/[unclosed.java' is not a usable glob: "));
        assertEquals("path " + other.resolve("A.txt") + " is outside this session's workspace,"
                + " which is " + repo + "; ask for the roots you have rather than guessing at"
                + " paths", refused(() -> provider.grep(new Needle("x", false),
                other.resolve("A.txt"))));
    }

    @Test
    void a_picture_the_client_named_is_one_line_worded_here_and_counted_as_one()
            throws IOException {
        Path logo = Files.write(repo.resolve("logo.png"), new byte[] {(byte) 0x89, 'P', 'N', 'G',
                0x0D, 0x0A, 0x1A, 0x0A, 0x00});

        Span read = provider.read(logo, FIRST);
        Span stat = provider.stat(logo);

        assertEquals(List.of("The file " + logo + " is a png image, so it is named rather than"
                + " read: no tool here answers with a picture. It was uploaded to the server and"
                + " its id is img_0000000000000042. Hand that id to an agent that can see, as"
                + " agent_run's 'images', and it will be shown the picture -- you will not. The"
                + " bytes were copied to get there, which is what a workspace on another machine"
                + " costs: editing this file now does not change what that id resolves to."),
                read.lines());
        assertEquals(1, stat.totalLines());
        assertEquals(1, provider.grep(new Needle("img_0000000000000042", false), logo)
                .matches().size());
    }

    @Test
    void a_workspace_gone_from_under_the_client_ends_the_run_in_the_client_s_old_words()
            throws IOException {
        Files.delete(repo);

        WorkspaceUnavailableException gone = assertThrows(WorkspaceUnavailableException.class,
                () -> provider.glob("**/*.java"));

        assertEquals("the workspace " + repo + " this session was set to is no longer there",
                gone.getMessage());
    }

    @Test
    void a_run_outside_the_workspace_is_worded_as_any_path_outside_it() {
        FileRequest run = FileRequest.run("r1", other.toString(), List.of("ls"), Map.of(),
                List.of(), 1_000L, 1_024L, false, null);
        FileReply reply = new ClientEnforcer(workspaceOf(repo)).answer(run);

        assertEquals("path " + other + " is outside this session's workspace, which is " + repo
                + "; ask for the roots you have rather than guessing at paths",
                FileWords.said(reply));
    }

    private static Workspace workspaceOf(Path root) {
        Workspace workspace = new Workspace();
        workspace.set(List.of(root));
        return workspace;
    }
}
