package io.aeyer.plowshare.server.files;

import io.aeyer.plowshare.protocol.FileReply;
import io.aeyer.plowshare.protocol.FileRequest;
import io.aeyer.plowshare.protocol.FileResult;
import io.aeyer.plowshare.protocol.FileSource;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.ImageFormat;
import io.aeyer.plowshare.server.images.ImageStore;
import io.aeyer.plowshare.server.images.UnstorableImageException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;

/** Server-side file interpretation. Fresh fenced source metadata is required on
 * every access, including cache hits. Raw bytes never become a model tool result. */
public final class FileContents {
    public static final FileContents SHARED = new FileContents(8 * 1024 * 1024);
    private static final Semaphore TRANSFERS = new Semaphore(4, true);
    private final int allowance;
    private int held;
    private final LinkedHashMap<String, Cached> cache = new LinkedHashMap<>(16, .75f, true);
    private record Cached(List<String> lines, String image, String format, int weight) {}

    public FileContents(int allowance) {
        if (allowance < 0) throw new IllegalArgumentException("negative text cache allowance");
        this.allowance = allowance;
    }

    public List<String> remote(String path, String op, Home home, ImageStore images,
            Function<FileRequest, FileReply> ask) {
        FileSource meta = ask.apply(FileRequest.source(id(), path, null, null)).source();
        if (meta == null || meta.data() != null) throw broken("missing source metadata");
        List<String> remembered = remembered(meta.sha256(), path, op, home, images, true);
        if (remembered != null) return remembered;
        try {
            if (!TRANSFERS.tryAcquire(30, java.util.concurrent.TimeUnit.SECONDS))
                throw broken("source transfer capacity was unavailable before its deadline");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new WorkspaceUnavailableException("waiting for source transfer was interrupted", interrupted);
        }
        try {
            byte[] bytes = new byte[(int) meta.size()];
            long started = System.nanoTime();
            for (int offset = 0; offset < bytes.length;) {
                if (System.nanoTime() - started > java.time.Duration.ofSeconds(30).toNanos())
                    throw broken("source transfer exceeded its deadline");
                int wanted = Math.min(FileSource.CHUNK_BYTES, bytes.length - offset);
                FileSource chunk = ask.apply(FileRequest.source(id(), path, offset, wanted)).source();
                if (chunk == null || chunk.data() == null || chunk.size() != bytes.length
                        || chunk.offset() == null || chunk.offset() != offset) throw broken("source range changed or was out of order");
                byte[] decoded;
                try { decoded = Base64.getDecoder().decode(chunk.data()); }
                catch (IllegalArgumentException malformed) { throw broken("source range was not base64"); }
                if (decoded.length != wanted) throw broken("source range was truncated or oversized");
                System.arraycopy(decoded, 0, bytes, offset, decoded.length);
                offset += decoded.length;
            }
            if (!sha256(bytes).equals(meta.sha256())) throw broken("source changed during transfer; no converted text was cached");
            return decode(path, op, bytes, home, images, true);
        } finally { TRANSFERS.release(); }
    }

    /** Local files use the same PDF cache, after the local provider's fence. */
    public List<String> pdf(String path, String op, byte[] bytes, boolean remote) {
        if (!isPdf(bytes)) return null;
        String key = "text:" + sha256(bytes);
        Cached saved = get(key);
        if (saved != null) return saved.lines();
        List<String> lines;
        try (var document = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setLineSeparator("\n"); stripper.setPageEnd("\n");
            lines = stripper.getText(document).replace("\r\n", "\n").replace('\r', '\n').lines().toList();
        } catch (InvalidPasswordException encrypted) {
            throw new FileRefusedException(FileResult.unreadable(op, FileResult.REFUSED, FileResult.ENCRYPTED, path, "PDF"), remote);
        } catch (IOException | RuntimeException damaged) {
            throw new FileRefusedException(FileResult.unreadable(op, FileResult.REFUSED, FileResult.DAMAGED, path, "PDF"), remote);
        }
        put(key, text(lines));
        return lines;
    }

    private List<String> decode(String path, String op, byte[] bytes, Home home, ImageStore images, boolean remote) {
        List<String> pdf = pdf(path, op, bytes, remote);
        if (pdf != null) return pdf;
        ImageFormat format = ImageFormat.of(bytes);
        if (format != null) {
            if (!images.keepsAnything()) throw new FileRefusedException(FileResult.unreadable(op, FileResult.NOT_TEXT, FileResult.UNCONVERTED, path, format.declared()), remote);
            String image;
            try { image = images.store(home, path, bytes).id(); }
            catch (UnstorableImageException refused) {
                throw new FileRefusedException(FileResult.imageRefused(op, path, format.declared(), switch (refused.reason()) { case EMPTY -> 400; case UNRECOGNISED -> 415; case TOO_LARGE -> 413; }, refused.getMessage(), bytes.length), remote);
            }
            put(imageKey(home, sha256(bytes)), new Cached(null, image, format.declared(), 1));
            return List.of(FileWords.named(FileResult.named(op, path, format.declared(), image), remote));
        }
        try {
            List<String> lines = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString().lines().toList();
            put("text:" + sha256(bytes), text(lines));
            return lines;
        } catch (CharacterCodingException binary) {
            throw new FileRefusedException(FileResult.notText(op, path, FileResult.NOT_UTF8), remote);
        }
    }

    private List<String> remembered(String hash, String path, String op, Home home, ImageStore images, boolean remote) {
        Cached text = get("text:" + hash);
        if (text != null) return text.lines();
        Cached image = get(imageKey(home, hash));
        if (image != null && images.find(home, image.image()).isPresent())
            return List.of(FileWords.named(FileResult.named(op, path, image.format(), image.image()), remote));
        return null;
    }
    private static String imageKey(Home home, String hash) { return "image:" + home + ":" + hash; }
    private static Cached text(List<String> lines) {
        long weight = 1;
        for (String line : lines) weight += line.length() + 1L;
        return new Cached(List.copyOf(lines), null, null, (int) Math.min(Integer.MAX_VALUE, weight));
    }
    private synchronized Cached get(String key) { return cache.get(key); }
    private synchronized void put(String key, Cached entry) {
        if (entry.weight() > allowance) return;
        Cached old = cache.remove(key);
        if (old != null) held -= old.weight();
        while (!cache.isEmpty() && (held + (long) entry.weight() > allowance || cache.size() >= 128)) {
            var iterator = cache.entrySet().iterator();
            held -= iterator.next().getValue().weight(); iterator.remove();
        }
        cache.put(key, entry); held += entry.weight();
    }
    synchronized int entries() { return cache.size(); }
    private static boolean isPdf(byte[] bytes) { return bytes.length >= 4 && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F'; }
    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String id() { return UUID.randomUUID().toString(); }
    private static WorkspaceUnavailableException broken(String reason) { return new WorkspaceUnavailableException(reason + "; nothing can be concluded from this file transfer"); }
}
