package io.aeyer.plowshare.server.media;

import static io.aeyer.plowshare.server.media.MediaInspectionException.Reason.ANIMATION;
import static io.aeyer.plowshare.server.media.MediaInspectionException.Reason.DIMENSIONS;
import static io.aeyer.plowshare.server.media.MediaInspectionException.Reason.INVALID_IMAGE;
import static io.aeyer.plowshare.server.media.MediaInspectionException.Reason.TOO_COMPLEX;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Framing and allocation bounds before JPEG/WebP pixel decoding. Metadata is excluded from the
 * transient decoder view, while the exact original bytes are hashed, stored and sent to models.
 * Animation and concatenated/multi-picture files are refused rather than choosing a frame.
 */
record StillImageValidation(int width, int height, byte[] decodingBytes) {
  static StillImageValidation jpeg(byte[] bytes, int maxPixels, int maxDimension) {
    var view = new ByteArrayOutputStream(bytes.length);
    view.write(bytes, 0, 2); // Shared signature detection established SOI.
    int at = 2;
    int width = 0;
    int height = 0;
    boolean scanned = false;
    for (int count = 0; at < bytes.length; count++) {
      if (count == 1024) throw new MediaInspectionException(TOO_COMPLEX);
      int start = at;
      if (u(bytes[at++]) != 255) throw invalid();
      while (at < bytes.length && u(bytes[at]) == 255) at++;
      if (at == bytes.length) throw invalid();
      int marker = u(bytes[at++]);
      if (marker == 0xd9) {
        if (!scanned || width == 0 || at != bytes.length) throw invalid();
        view.write(bytes, start, at - start);
        return new StillImageValidation(width, height, view.toByteArray());
      }
      if (marker == 0
          || marker == 0xd8
          || marker >= 0xd0 && marker <= 0xd7
          || at + 2 > bytes.length) throw invalid();
      int length = u(bytes[at]) * 256 + u(bytes[at + 1]);
      if (length < 2 || length > bytes.length - at) throw invalid();
      int data = at + 2;
      int end = at + length;
      if (marker >= 0xc0 && marker <= 0xcf && marker != 0xc4 && marker != 0xc8 && marker != 0xcc) {
        // JDK decoder scope: 8-bit baseline/extended sequential/progressive, grayscale or RGB.
        if (width != 0 || marker > 0xc2 || length < 8 || u(bytes[data]) != 8) throw invalid();
        height = u(bytes[data + 1]) * 256 + u(bytes[data + 2]);
        width = u(bytes[data + 3]) * 256 + u(bytes[data + 4]);
        int components = u(bytes[data + 5]);
        if ((components != 1 && components != 3) || length != 8 + 3 * components) throw invalid();
        dimensions(width, height, maxPixels, maxDimension);
      }
      boolean metadata = marker >= 0xe0 && marker <= 0xef || marker == 0xfe;
      if (marker == 0xe2
          && length >= 6
          && bytes[data] == 'M'
          && bytes[data + 1] == 'P'
          && bytes[data + 2] == 'F'
          && bytes[data + 3] == 0) {
        throw new MediaInspectionException(ANIMATION);
      }
      if (marker == 0xdc) throw invalid(); // DNL can change geometry after allocation checks.
      if (!metadata) view.write(bytes, start, end - start);
      at = end;
      if (marker == 0xda) {
        if (width == 0) throw invalid();
        scanned = true;
        int entropyStart = at;
        while (at < bytes.length) {
          if (u(bytes[at]) != 255) {
            at++;
            continue;
          }
          int next = at + 1;
          while (next < bytes.length && u(bytes[next]) == 255) next++;
          if (next == bytes.length) throw invalid();
          int value = u(bytes[next]);
          if (value == 0 || value >= 0xd0 && value <= 0xd7) {
            at = next + 1;
            continue;
          }
          break;
        }
        view.write(bytes, entropyStart, at - entropyStart);
      }
    }
    throw invalid();
  }

  static StillImageValidation webp(byte[] bytes, int maxPixels, int maxDimension) {
    if (bytes.length < 20) throw invalid();
    var input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    if (Integer.toUnsignedLong(input.getInt(4)) + 8 != bytes.length) throw invalid();
    var view = new ByteArrayOutputStream(bytes.length);
    view.write(bytes, 0, 12);
    int canvasWidth = 0;
    int canvasHeight = 0;
    int width = 0;
    int height = 0;
    boolean alpha = false;
    boolean rawAlpha = false;
    int flags = 0;
    int at = 12;
    for (int count = 0; at < bytes.length; count++) {
      if (count == 1024) throw new MediaInspectionException(TOO_COMPLEX);
      if (bytes.length - at < 8) throw invalid();
      String type = new String(bytes, at, 4, StandardCharsets.US_ASCII);
      long size = Integer.toUnsignedLong(input.getInt(at + 4));
      long end = at + 8L + size + (size & 1);
      if (end > bytes.length || size > Integer.MAX_VALUE) throw invalid();
      int length = (int) size;
      int data = at + 8;
      if ((size & 1) != 0 && bytes[(int) end - 1] != 0) throw invalid();
      switch (type) {
        case "ANIM", "ANMF" -> throw new MediaInspectionException(ANIMATION);
        case "VP8X" -> {
          if (at != 12 || length != 10) throw invalid();
          flags = u(bytes[data]);
          if ((flags & 2) != 0) throw new MediaInspectionException(ANIMATION);
          if ((flags & 0xc1) != 0
              || bytes[data + 1] != 0
              || bytes[data + 2] != 0
              || bytes[data + 3] != 0) throw invalid();
          canvasWidth = 1 + uint24(bytes, data + 4);
          canvasHeight = 1 + uint24(bytes, data + 7);
          dimensions(canvasWidth, canvasHeight, maxPixels, maxDimension);
          byte[] header = java.util.Arrays.copyOfRange(bytes, at, (int) end);
          header[8] &= 0x10; // Only alpha survives in the metadata-free decoder view.
          view.writeBytes(header);
        }
        case "ALPH" -> {
          if (canvasWidth == 0 || alpha || width != 0 || length == 0 || (flags & 0x10) == 0)
            throw invalid();
          alpha = true;
          int control = u(bytes[data]);
          if ((control & 0xc0) != 0 || (control & 3) > 1 || ((control >>> 4) & 3) > 1)
            throw invalid();
          rawAlpha = (control & 3) == 0;
          if (rawAlpha) {
            // TwelveMonkeys refuses uncompressed alpha. It is exactly one byte per pixel,
            // with optional reversible filtering, so framing/length fully validates that plane.
            // Validate color pixels separately; never modify the retained/sent original file.
            if (length != 1L + (long) canvasWidth * canvasHeight) throw invalid();
          } else view.write(bytes, at, (int) end - at);
        }
        case "VP8 ", "VP8L" -> {
          if (width != 0 || canvasWidth == 0 && at != 12) throw invalid();
          if (type.equals("VP8 ")) {
            if (length < 10
                || (u(bytes[data]) & 1) != 0
                || u(bytes[data + 3]) != 0x9d
                || u(bytes[data + 4]) != 1
                || u(bytes[data + 5]) != 0x2a) throw invalid();
            width = Short.toUnsignedInt(input.getShort(data + 6)) & 0x3fff;
            height = Short.toUnsignedInt(input.getShort(data + 8)) & 0x3fff;
            if (canvasWidth != 0 && ((flags & 0x10) != 0) != alpha) throw invalid();
          } else {
            if (length < 5 || alpha || u(bytes[data]) != 0x2f) throw invalid();
            int bits = input.getInt(data + 1);
            if ((bits >>> 29) != 0) throw invalid();
            width = (bits & 0x3fff) + 1;
            height = ((bits >>> 14) & 0x3fff) + 1;
          }
          dimensions(width, height, maxPixels, maxDimension);
          if (canvasWidth != 0 && (width != canvasWidth || height != canvasHeight)) throw invalid();
          view.write(bytes, at, (int) end - at);
        }
        default -> {
          /* Bounded opaque metadata/unknown chunks are not parsed or expanded. */
        }
      }
      at = (int) end;
    }
    if (width == 0) throw invalid();
    byte[] sanitized = view.toByteArray();
    if (rawAlpha) sanitized[20] &= ~0x10;
    ByteBuffer.wrap(sanitized).order(ByteOrder.LITTLE_ENDIAN).putInt(4, sanitized.length - 8);
    return new StillImageValidation(width, height, sanitized);
  }

  private static int uint24(byte[] bytes, int at) {
    return u(bytes[at]) | u(bytes[at + 1]) << 8 | u(bytes[at + 2]) << 16;
  }

  private static int u(byte value) {
    return value & 255;
  }

  private static void dimensions(int width, int height, int maxPixels, int maxDimension) {
    if (width < 1 || height < 1) throw invalid();
    if (width > maxDimension || height > maxDimension || (long) width * height > maxPixels)
      throw new MediaInspectionException(DIMENSIONS);
  }

  private static MediaInspectionException invalid() {
    return new MediaInspectionException(INVALID_IMAGE);
  }
}
