package io.aeyer.plowshare.server.media;

import static io.aeyer.plowshare.server.media.MediaInspectionException.Reason.ANIMATION;
import static io.aeyer.plowshare.server.media.MediaInspectionException.Reason.DIMENSIONS;
import static io.aeyer.plowshare.server.media.MediaInspectionException.Reason.INVALID_IMAGE;
import static io.aeyer.plowshare.server.media.MediaInspectionException.Reason.TOO_COMPLEX;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * PNG framing, CRC, animation and expansion checks before invoking the pixel decoder. The decoder
 * alone can tolerate missing trailers or ignore extra compressed content; admission must refuse it.
 */
record PngValidation(int width, int height, List<Chunk> decodingChunks) {
  private static final int MAX_CHUNKS = 1024;

  /** Offsets into the caller's private immutable snapshot; no image data is copied here. */
  record Chunk(int offset, int length) {}

  static PngValidation validate(byte[] bytes, int maxPixels, int maxDimension) {
    ByteBuffer input = ByteBuffer.wrap(bytes);
    input.position(8); // The detector and shared signature check have already established PNG.
    int width = 0;
    int height = 0;
    int depth = 0;
    int color = 0;
    int interlace = 0;
    int paletteEntries = 0;
    boolean transparency = false;
    boolean dataSeen = false;
    boolean dataEnded = false;
    var decoding = new ArrayList<Chunk>();
    var compressed = new ArrayList<Chunk>();
    for (int count = 0; input.hasRemaining(); count++) {
      if (count == MAX_CHUNKS) throw new MediaInspectionException(TOO_COMPLEX);
      int start = input.position();
      if (input.remaining() < 12) throw invalid();
      int length = input.getInt();
      if (length < 0 || length > input.remaining() - 8) throw invalid();
      int typeAt = input.position();
      for (int i = 0; i < 4; i++) {
        int c = input.get() & 0xff;
        if (!(c >= 'A' && c <= 'Z') && !(c >= 'a' && c <= 'z')) throw invalid();
        if (i == 2 && c >= 'a') throw invalid(); // PNG's reserved chunk-type bit.
      }
      String type = new String(bytes, typeAt, 4, StandardCharsets.US_ASCII);
      int dataAt = input.position();
      CRC32 crc = new CRC32();
      crc.update(bytes, typeAt, length + 4);
      if (crc.getValue() != Integer.toUnsignedLong(input.getInt(dataAt + length))) throw invalid();
      input.position(dataAt + length + 4);
      if (count == 0 && !type.equals("IHDR")) throw invalid();
      if (dataSeen && !type.equals("IDAT")) dataEnded = true;
      boolean decode = false;
      switch (type) {
        case "IHDR" -> {
          if (count != 0 || length != 13) throw invalid();
          ByteBuffer header = ByteBuffer.wrap(bytes, dataAt, length);
          width = header.getInt();
          height = header.getInt();
          if (width < 1 || height < 1) throw invalid();
          if (width > maxDimension || height > maxDimension || (long) width * height > maxPixels) {
            throw new MediaInspectionException(DIMENSIONS);
          }
          depth = header.get() & 0xff;
          color = header.get() & 0xff;
          boolean validDepth =
              switch (color) {
                case 0 -> depth == 1 || depth == 2 || depth == 4 || depth == 8 || depth == 16;
                case 2, 4, 6 -> depth == 8 || depth == 16;
                case 3 -> depth == 1 || depth == 2 || depth == 4 || depth == 8;
                default -> false;
              };
          if (!validDepth || header.get() != 0 || header.get() != 0) throw invalid();
          interlace = header.get() & 0xff;
          if (interlace > 1) throw invalid();
          decode = true;
        }
        case "acTL", "fcTL", "fdAT" -> throw new MediaInspectionException(ANIMATION);
        case "PLTE" -> {
          if (dataSeen
              || paletteEntries != 0
              || transparency
              || color == 0
              || color == 4
              || length < 3
              || length > 768
              || length % 3 != 0) throw invalid();
          paletteEntries = length / 3;
          if (color == 3 && paletteEntries > (1 << depth)) throw invalid();
          decode = true;
        }
        case "tRNS" -> {
          if (dataSeen || transparency) throw invalid();
          boolean valid =
              switch (color) {
                case 0 -> length == 2;
                case 2 -> length == 6;
                case 3 -> paletteEntries > 0 && length > 0 && length <= paletteEntries;
                default -> false;
              };
          if (!valid) throw invalid();
          transparency = true;
          decode = true;
        }
        case "IDAT" -> {
          if (dataEnded || (color == 3 && paletteEntries == 0)) throw invalid();
          dataSeen = true;
          compressed.add(new Chunk(dataAt, length));
          decode = true;
        }
        case "IEND" -> {
          if (length != 0 || !dataSeen || input.hasRemaining()) throw invalid();
          decoding.add(new Chunk(start, length + 12));
          validateExpansion(
              bytes, compressed, scanlineBytes(width, height, depth, color, interlace));
          return new PngValidation(width, height, List.copyOf(decoding));
        }
        default -> {
          if (bytes[typeAt] >= 'A' && bytes[typeAt] <= 'Z') throw invalid();
          // Unknown ancillary chunks are CRC-checked but never parsed, expanded or exposed.
        }
      }
      if (decode) decoding.add(new Chunk(start, length + 12));
    }
    throw invalid(); // Missing IEND is never repaired or accepted.
  }

  /**
   * Give Image I/O only structural/pixel chunks. Original metadata remains in the hashed original
   * bytes, but text/ICC/EXIF expansion cannot consume resources during validation. This transient
   * view neither changes stored bytes nor resizes/re-encodes the image.
   */
  InputStream decodingStream(byte[] bytes) {
    var streams = new ArrayList<InputStream>();
    streams.add(new ByteArrayInputStream(bytes, 0, 8));
    for (Chunk chunk : decodingChunks) {
      streams.add(new ByteArrayInputStream(bytes, chunk.offset(), chunk.length()));
    }
    return new SequenceInputStream(Collections.enumeration(streams));
  }

  private static long scanlineBytes(int width, int height, int depth, int color, int interlace) {
    int channels =
        switch (color) {
          case 0, 3 -> 1;
          case 2 -> 3;
          case 4 -> 2;
          case 6 -> 4;
          default -> throw invalid();
        };
    if (interlace == 0) return rows(width, height, channels * depth);
    // Adam7's seven disjoint passes; empty passes contain no filter byte.
    int[] x = {0, 4, 0, 2, 0, 1, 0};
    int[] y = {0, 0, 4, 0, 2, 0, 1};
    int[] dx = {8, 8, 4, 4, 2, 2, 1};
    int[] dy = {8, 8, 8, 4, 4, 2, 2};
    long total = 0;
    for (int pass = 0; pass < 7; pass++) {
      int w = Math.max(0, (width - x[pass] + dx[pass] - 1) / dx[pass]);
      int h = Math.max(0, (height - y[pass] + dy[pass] - 1) / dy[pass]);
      if (w > 0 && h > 0) total += rows(w, h, channels * depth);
    }
    return total;
  }

  private static long rows(int width, int height, int bitsPerPixel) {
    return (((long) width * bitsPerPixel + 7) / 8 + 1) * height;
  }

  private static void validateExpansion(byte[] bytes, List<Chunk> chunks, long expected) {
    Inflater inflater = new Inflater();
    byte[] buffer = new byte[8192];
    long expanded = 0;
    try {
      for (Chunk chunk : chunks) {
        if (chunk.length() == 0) continue;
        if (inflater.finished()) throw invalid();
        inflater.setInput(bytes, chunk.offset(), chunk.length());
        while (!inflater.needsInput()) {
          int read = inflater.inflate(buffer);
          expanded += read;
          if (expanded > expected) throw invalid();
          if (inflater.finished()) {
            if (inflater.getRemaining() != 0) throw invalid();
            break;
          }
          if (inflater.needsDictionary() || (read == 0 && !inflater.needsInput())) throw invalid();
        }
      }
      if (!inflater.finished() || expanded != expected) throw invalid();
    } catch (DataFormatException invalid) {
      throw invalid();
    } finally {
      inflater.end();
    }
  }

  private static MediaInspectionException invalid() {
    return new MediaInspectionException(INVALID_IMAGE);
  }
}
