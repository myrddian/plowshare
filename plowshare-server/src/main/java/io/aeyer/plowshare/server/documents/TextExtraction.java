package io.aeyer.plowshare.server.documents;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Bytes into text, for the formats this slice reads — and a refusal that says
 * what to do for the ones it does not.
 *
 * <h2>What this slice accepts, said plainly</h2>
 *
 * <p><b>Text-like formats — anything that is valid UTF-8 and contains no NUL
 * byte — and PDF.</b> Markdown, plain text, source, CSV, JSON, HTML-as-text,
 * and a PDF through {@link PdfExtraction}. Nothing else, and in particular no
 * Tika.
 *
 * <h2>Why PDF and only PDF</h2>
 *
 * <p>Anchor's answer is {@code DocumentTextExtractor} dispatching on {@code
 * .pdf} between {@code PdfTextExtractor} (PDFBox) and {@code TikaTextExtractor}
 * (everything else). Half of that ports. The other half is the expensive one:
 * resolved from Anchor's own runtime classpath on 2026-09-04, {@code
 * tika-parsers-standard-package} brings <b>eighty</b> modules, and Anchor's
 * runtime classpath is 196 modules against this server's 58. PDFBox is three of
 * those and 3.59 MiB, measured on this tree.
 *
 * <p><b>PDF is here because Anchor's corpus is PDFs of academic papers</b>, and
 * a port that cannot ingest one cannot be evaluated against the thing it is a
 * port of. It is also the only format that carries an <em>outline</em>, which
 * {@code ChapterDetector}'s second precedence branch reads and which nothing
 * downstream can reconstruct from text.
 *
 * <p><b>This does not replace the client-side adaptor and does not duplicate
 * it</b>, and the two answer different questions. {@code
 * plowshare-client}'s {@code files.PdfConverter} converts a file the client
 * already holds, for {@code file_read}'s line-based windows, and collects no
 * outline; it also, deliberately, does not preserve paragraph breaks, because a
 * window is supposed to be the file's own lines. This one runs where multipart
 * bytes arrive, collects the outline, and preserves paragraph breaks because
 * {@link Derivation} splits on them. Sharing them would mean a converter with a
 * flag whose two settings are that disagreement, in a module — {@code
 * plowshare-protocol} — whose build file says it holds Jackson annotations and
 * nothing else. See {@link PdfExtraction} for the measurements.
 *
 * <p><b>What every other format still needs</b>, stated here because this class
 * is the seam a client-side adaptor would deliver into: whole text in one
 * delivery rather than windows, the SHA-256 of the <em>original</em> bytes (this
 * pipeline's dedup gate is the source file, not its conversion), and a name for
 * what converted it — {@link Extracted#converter()}. Two things it must not
 * imply: an offset into converted text is not a coordinate in the source file —
 * line 400 of an extraction is not page 3 line 10 of a PDF — and which formats
 * convert becomes a property of the connected client rather than of this server,
 * so it is something a presence has to declare rather than something a caller
 * may assume.
 *
 * <h2>The format is read off the bytes</h2>
 *
 * <p>An extension is a claim the sender makes and a signature is what the file
 * is. Dispatching on the name would accept a PDF called {@code notes.txt} and
 * hand the paragraph splitter a stream of binary object headers, which produces
 * a corpus rather than an error. <b>Anchor dispatches on {@code
 * .toLowerCase().endsWith(".pdf")}</b>, and that is one of the two things this
 * class does not port; now that the PDF branch reads rather than refuses, the
 * rule cuts the other way too — a paper saved as {@code paper} with no extension
 * is read, and a {@code .pdf} that is really a Word file is not.
 *
 * <h2>The image refusal still stands, and now says where images do go</h2>
 *
 * <p>"an image carries no text for a corpus to hold" is unchanged by this
 * server having learned to show a model a picture. A vision <em>mechanism</em>
 * does not oblige this class to change: what a person does with a model that
 * sees is policy, and OCR, figure extraction and captioning are all policy this
 * server has not been asked for. What has changed is that the refusal is no
 * longer a dead end — {@code POST /v1/images} holds the bytes and answers with a
 * UID, and an agent is handed that UID rather than the file. The two doors stay
 * separate on purpose: a corpus is text, and an image store is not a corpus
 * with a gap in it.
 *
 * <p>Static, and not a {@code @Service}, for {@link Chunker}'s reason: there is
 * no state and nothing to inject.
 */
public final class TextExtraction {

    private TextExtraction() {
    }

    /**
     * One signature this server recognises well enough to turn away by name.
     *
     * <p>The list is short on purpose. It is not format detection — nothing here
     * <em>reads</em> any of these — it exists so the refusal is actionable. An
     * unrecognised binary still gets turned away by the UTF-8 or NUL checks
     * below, just with a less useful sentence.
     */
    private record Signature(byte[] magic, String name, String advice) {

        boolean matches(byte[] bytes) {
            if (bytes.length < magic.length) {
                return false;
            }
            for (int i = 0; i < magic.length; i++) {
                if (bytes[i] != magic[i]) {
                    return false;
                }
            }
            return true;
        }
    }

    private static final String CONVERT_FIRST =
            "this server ingests text and converts PDFs, and nothing else; convert it on the side"
                    + " that holds the file and upload the text";

    private static final List<Signature> KNOWN = List.of(
            // No PDF entry: it moved from this list to PdfExtraction, which is
            // the same five bytes recognised for the opposite purpose.
            // DOCX, XLSX, PPTX, EPUB, ODT and a plain .zip are all this.
            // Answered as one, because from here they are one thing: a
            // container nothing in this server opens.
            new Signature(new byte[] {'P', 'K', 0x03, 0x04}, "a zip container (DOCX, EPUB, ODT"
                    + " and friends are zip containers)", CONVERT_FIRST),
            new Signature(new byte[] {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0},
                    "a legacy Office document", CONVERT_FIRST),
            new Signature(new byte[] {'%', '!', 'P', 'S'}, "a PostScript file", CONVERT_FIRST),
            new Signature(new byte[] {'{', '\\', 'r', 't', 'f'}, "an RTF file", CONVERT_FIRST),
            new Signature(new byte[] {(byte) 0x89, 'P', 'N', 'G'}, "a PNG image",
                    "an image carries no text for a corpus to hold"),
            new Signature(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}, "a JPEG image",
                    "an image carries no text for a corpus to hold"),
            new Signature(new byte[] {'G', 'I', 'F', '8'}, "a GIF image",
                    "an image carries no text for a corpus to hold"),
            new Signature(new byte[] {0x7F, 'E', 'L', 'F'}, "an executable",
                    "there is nothing here to read"),
            new Signature(new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE},
                    "a compiled class file", "there is nothing here to read"));

    /**
     * The document, or a refusal naming it.
     *
     * @param sourceName what the sender called it. Used for the title and for
     *     every refusal message, never for deciding the format
     * @param bytes the upload, exactly as it arrived
     * @throws UnreadableDocumentException for an empty upload, an unnamed one,
     *     a recognised binary format this server does not read, bytes that are
     *     not UTF-8, text that carries a NUL, and a PDF that is encrypted,
     *     damaged or carries no text. Every one of those is a fact about the
     *     document
     */
    public static Extracted extract(String sourceName, byte[] bytes) {
        if (sourceName == null || sourceName.isBlank()) {
            throw new UnreadableDocumentException(
                    "a document has to arrive under a name: it is what the corpus files it as,"
                            + " and what a re-ingest of the same document matches on");
        }
        String name = sourceName.trim();
        if (bytes == null || bytes.length == 0) {
            throw new UnreadableDocumentException(
                    "'" + name + "' is empty, so there is nothing to ingest");
        }

        // Before the refusals, because it is the one signature that is an
        // instruction to read rather than an instruction to turn away.
        if (PdfExtraction.recognises(bytes)) {
            return PdfExtraction.extract(name, bytes, sha256(bytes), titleFrom(name));
        }

        for (Signature signature : KNOWN) {
            if (signature.matches(bytes)) {
                throw new UnreadableDocumentException(
                        "'" + name + "' is " + signature.name() + " — " + signature.advice());
            }
        }

        String decoded = decode(name, bytes);
        int nul = decoded.indexOf('\0');
        if (nul >= 0) {
            throw new UnreadableDocumentException(
                    "'" + name + "' holds a NUL byte at offset " + nul + ", so it is not text —"
                            + " a UTF-16 or binary file can decode as legal UTF-8 and become a"
                            + " corpus of mojibake that nothing downstream can tell from prose");
        }

        // The BOM after the NUL check and before the blank check: it is a
        // character, so a file holding nothing else is blank and should say so.
        if (!decoded.isEmpty() && decoded.charAt(0) == '﻿') {
            decoded = decoded.substring(1);
        }
        // \r\n and a lone \r both become \n, so "a blank line is a paragraph
        // break" is one rule rather than three.
        String text = decoded.replace("\r\n", "\n").replace('\r', '\n');
        if (text.isBlank()) {
            throw new UnreadableDocumentException(
                    "'" + name + "' holds no text, only whitespace");
        }

        // No outline, and NOT_CONVERTED: a text file has no structure to lift
        // and nothing converted it, so an offset into this text IS a position in
        // the bytes that arrived. That is the one case in which it is.
        return new Extracted(titleFrom(name), sha256(bytes), text, List.of(),
                Extracted.NOT_CONVERTED);
    }

    /**
     * Strict UTF-8, and strict is the whole point.
     *
     * <p>{@code new String(bytes, UTF_8)} replaces every malformed sequence with
     * U+FFFD and reports nothing, so a Latin-1 document becomes a document full
     * of replacement characters, gets chunked, gets embedded, and sits in the
     * corpus being retrieved for questions it cannot answer. A decoder set to
     * {@link CodingErrorAction#REPORT} turns that into a sentence somebody can
     * act on.
     */
    private static String decode(String name, byte[] bytes) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer decoded = decoder.decode(ByteBuffer.wrap(bytes));
            return decoded.toString();
        } catch (CharacterCodingException notUtf8) {
            throw new UnreadableDocumentException(
                    "'" + name + "' is not valid UTF-8 (" + notUtf8.getMessage() + "); this slice"
                            + " ingests text and re-encoding a document is a decision about its"
                            + " content rather than a repair — convert it and upload the result");
        }
    }

    /**
     * The name, without its extension, as a title.
     *
     * <p>Anchor takes the document's own metadata title first and falls back to
     * this, and so does {@link PdfExtraction}, which is handed the result of
     * this method as its fallback. A text file carries no metadata to take, so
     * for that path this is the whole of it and a document that deserves a
     * better title gets one from whoever uploads it.
     */
    private static String titleFrom(String name) {
        String base = name;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        int dot = base.lastIndexOf('.');
        String stem = dot > 0 ? base.substring(0, dot) : base;
        String title = stem.replace('_', ' ').replace('-', ' ').trim();
        // A dotfile — ".gitignore" — has no stem to speak of. Its own name is
        // a better title than nothing, and Extracted refuses a blank one.
        return title.isEmpty() ? base : title;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
                    .toLowerCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException impossible) {
            // Every JVM ships SHA-256; this is not a condition a caller can act
            // on and dressing it as an UnreadableDocumentException would blame
            // the document for the platform.
            throw new IllegalStateException("SHA-256 is unavailable on this JVM", impossible);
        }
    }
}
