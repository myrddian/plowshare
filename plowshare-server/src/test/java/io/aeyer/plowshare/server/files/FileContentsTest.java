package io.aeyer.plowshare.server.files;

import static org.junit.jupiter.api.Assertions.*;
import io.aeyer.plowshare.protocol.*;
import io.aeyer.plowshare.server.documents.Pdfs;
import io.aeyer.plowshare.server.images.ImageStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileContentsTest {
    private static final class Source implements Function<FileRequest, FileReply> {
        byte[] bytes;
        final List<FileRequest> asked = new ArrayList<>();
        Source(byte[] bytes) { this.bytes = bytes; }
        @Override public FileReply apply(FileRequest request) {
            asked.add(request);
            assertEquals(FileRequest.SOURCE, request.op());
            if (request.offset() == null) return FileReply.source(request.id(), new FileSource(bytes.length, FileContents.sha256(bytes), null, null));
            int end = Math.min(bytes.length, request.offset() + request.limit());
            return FileReply.source(request.id(), new FileSource(bytes.length, null, request.offset(), Base64.getEncoder().encodeToString(Arrays.copyOfRange(bytes, request.offset(), end))));
        }
    }
    private static List<String> read(FileContents contents, Source source) {
        return contents.remote("notes.pdf", FileRequest.READ, Home.global(), ImageStore.NONE, source);
    }
    @Test void pdf_is_converted_on_server_and_a_cache_hit_rechecks_source_without_retransferring() {
        Source source = new Source(Pdfs.of("Server conversion"));
        FileContents contents = new FileContents(1024);
        assertEquals(List.of("Server conversion"), read(contents, source));
        int transferred = source.asked.size();
        assertEquals(List.of("Server conversion"), read(contents, source));
        assertEquals(transferred + 1, source.asked.size());
        assertNull(source.asked.getLast().offset());
        source.bytes = Pdfs.of("Changed conversion");
        assertEquals(List.of("Changed conversion"), read(contents, source));
        assertEquals(2, contents.entries());
    }
    @Test void text_is_transferred_in_bounded_ranges_and_window_coordinates_remain_text_lines() {
        Source source = new Source(("line\n".repeat(40000)).getBytes(StandardCharsets.UTF_8));
        FileContents contents = new FileContents(1024 * 1024);
        List<String> lines = read(contents, source);
        assertEquals(40000, lines.size());
        assertEquals(5, source.asked.size());
        assertTrue(source.asked.stream().skip(1).allMatch(request -> request.limit() <= FileSource.CHUNK_BYTES));
        Span page = new Window(39999, 20).cut(lines);
        assertEquals(List.of("line"), page.lines());
        assertFalse(page.more());
    }
    @Test void changed_bytes_and_invalid_ranges_never_enter_the_conversion_cache() {
        Source source = new Source(Pdfs.of("Before"));
        FileContents contents = new FileContents(1024);
        Function<FileRequest, FileReply> changing = request -> {
            FileReply response = source.apply(request);
            if (request.offset() == null) source.bytes = Pdfs.of("Afters");
            return response;
        };
        assertThrows(WorkspaceUnavailableException.class, () -> contents.remote("notes.pdf", FileRequest.READ, Home.global(), ImageStore.NONE, changing));
        assertEquals(0, contents.entries());
        assertEquals(List.of("Afters"), read(contents, source));
        FileContents other = new FileContents(1024);
        assertThrows(WorkspaceUnavailableException.class, () -> other.remote("x", FileRequest.READ, Home.global(), ImageStore.NONE, request -> request.offset() == null ? source.apply(request) : FileReply.source(request.id(), new FileSource(source.bytes.length, null, request.offset() + 1, ""))));
        assertEquals(0, other.entries());
    }
    @Test void eviction_retransfers_and_reconverts_while_oversized_entries_are_served_uncached() {
        FileContents contents = new FileContents(8);
        Source a = new Source(Pdfs.of("first")), b = new Source(Pdfs.of("other"));
        assertEquals(List.of("first"), read(contents, a));
        assertEquals(List.of("other"), read(contents, b));
        assertEquals(1, contents.entries());
        int before = a.asked.size();
        read(contents, a);
        assertEquals(before + 2, a.asked.size());
        Source huge = new Source(Pdfs.of("Too large for cache"));
        assertEquals(List.of("Too large for cache"), read(contents, huge));
        int transfers = huge.asked.size();
        read(contents, huge);
        assertEquals(transfers + 2, huge.asked.size());
    }
    @Test void a_scan_preserves_the_existing_empty_text_read_and_damaged_pdf_is_correctable() {
        assertEquals(List.of(""), read(new FileContents(1024), new Source(Pdfs.noText())));
        FileRefusedException damaged = assertThrows(FileRefusedException.class, () -> read(new FileContents(1024), new Source(Pdfs.broken())));
        assertEquals(FileResult.DAMAGED, damaged.facts().reason());
    }
    @Test void image_bytes_are_stored_server_side_with_home_isolation_and_one_line_results(@TempDir Path root) {
        ImageStore images = new ImageStore(home -> root.resolve(home.isGlobal() ? "global" : home.project()), 4096);
        byte[] png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jfJkAAAAASUVORK5CYII=");
        Source source = new Source(png);
        FileContents contents = new FileContents(1024);
        List<String> result = contents.remote("pixel.png", FileRequest.READ, Home.of("one"), images, source);
        String uid = ImageStore.idFor(png);
        assertEquals(1, result.size());
        assertTrue(result.getFirst().contains(uid));
        assertTrue(images.find(Home.of("one"), uid).isPresent());
        assertTrue(images.find(Home.of("two"), uid).isEmpty());
        int before = source.asked.size();
        contents.remote("copy.png", FileRequest.STAT, Home.of("one"), images, source);
        assertEquals(before + 1, source.asked.size());
        contents.remote("pixel.png", FileRequest.READ, Home.of("two"), images, source);
        assertTrue(images.find(Home.of("two"), uid).isPresent());
        assertEquals(before + 3, source.asked.size());
    }
    @Test void source_size_base64_and_chunk_bounds_are_not_silently_truncated() {
        assertThrows(IllegalArgumentException.class, () -> new FileSource(FileSource.MAX_BYTES + 1L, "a".repeat(64), null, null));
        Source source = new Source(new byte[]{1});
        FileContents contents = new FileContents(1024);
        for (String data : List.of("not base64", "", "AQID"))
            assertThrows(WorkspaceUnavailableException.class, () -> contents.remote("x", FileRequest.READ, Home.global(), ImageStore.NONE, request -> request.offset() == null ? source.apply(request) : FileReply.source(request.id(), new FileSource(1, null, 0, data))));
        assertEquals(0, contents.entries());
    }
    @Test void a_cache_hit_never_bypasses_the_callers_grant_or_a_fresh_refusal() {
        FileContents contents = new FileContents(1024);
        Source source = new Source(Pdfs.of("Private text"));
        read(contents, source);
        SessionChannel channel = new SessionChannel() {
            public boolean sources(String session) { return true; }
            public FileReply ask(String session, FileRequest request) { return FileReply.refused(request.id(), FileResult.refused(FileRequest.READ, FileResult.OUTSIDE, request.path())); }
        };
        var read = List.of(new Grant(Scope.WORKSPACE, Mode.READ));
        assertThrows(WorkspaceRefusedException.class, () -> new RemoteProvider(channel, "s", List.of(), Home.global(), ImageStore.NONE, contents).read(Path.of("notes.pdf"), new Window(0, 1)));
        assertThrows(FileRefusedException.class, () -> new RemoteProvider(channel, "s", read, Home.global(), ImageStore.NONE, contents).read(Path.of("notes.pdf"), new Window(0, 1)));
    }

    @Test void server_local_pdf_reads_use_the_same_conversion_and_text_windows(@TempDir Path root) throws Exception {
        Path pdf = root.resolve("local.pdf");
        java.nio.file.Files.write(pdf, Pdfs.of("Local server conversion"));
        LocalProvider local = LocalProvider.over(FileAccess.of(List.of(root), List.of()), List.of(new Grant(Scope.WORKSPACE, Mode.READ)));
        assertEquals(List.of("Local server conversion"), local.read(pdf, new Window(0, 1)).lines());
        assertEquals(1, local.stat(pdf).totalLines());
        assertEquals(1, local.grep(new Needle("conversion", false), pdf).matches().size());
    }

    @Test void encrypted_pdf_remains_a_correctable_file_refusal_without_caching() throws Exception {
        byte[] encrypted;
        try (var document = org.apache.pdfbox.Loader.loadPDF(Pdfs.of("Secret"))) {
            var policy = new org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy("owner", "password", new org.apache.pdfbox.pdmodel.encryption.AccessPermission());
            document.protect(policy);
            var out = new java.io.ByteArrayOutputStream();
            document.save(out); encrypted = out.toByteArray();
        }
        FileContents contents = new FileContents(1024);
        FileRefusedException failure = assertThrows(FileRefusedException.class, () -> read(contents, new Source(encrypted)));
        assertEquals(FileResult.ENCRYPTED, failure.facts().reason());
        assertEquals(0, contents.entries());
    }

    @Test void disconnect_mid_transfer_never_yields_a_partial_file_or_populates_cache() {
        Source source = new Source(("line\n".repeat(30000)).getBytes(StandardCharsets.UTF_8));
        FileContents contents = new FileContents(1024 * 1024);
        assertThrows(SessionGoneException.class, () -> contents.remote("log", FileRequest.READ, Home.global(), ImageStore.NONE,
                request -> { if (request.offset() != null && request.offset() > 0) throw new SessionGoneException("closed"); return source.apply(request); }));
        assertEquals(0, contents.entries());
        assertEquals(30000, read(contents, source).size());
    }
}
