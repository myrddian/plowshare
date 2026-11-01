package io.aeyer.plowshare.server.media;

import static io.aeyer.plowshare.server.media.MediaInspectionException.Reason.BUSY;
import static io.aeyer.plowshare.server.media.MediaInspectionException.Reason.EMPTY;
import static io.aeyer.plowshare.server.media.MediaInspectionException.Reason.INVALID_IMAGE;
import static io.aeyer.plowshare.server.media.MediaInspectionException.Reason.TOO_LARGE;
import static io.aeyer.plowshare.server.media.MediaInspectionException.Reason.UNSUPPORTED_FORMAT;

import io.aeyer.plowshare.protocol.ImageFormat;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.concurrent.Semaphore;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MimeTypes;
import org.apache.tika.parser.ParseContext;

/**
 * Tika core byte detection plus bounded still-image validation and allowlisted pixel decoders.
 *
 * <p>No Tika parser service discovery is used. The optional image parser would add metadata
 * extraction, but image dimensions and full decoding already belong to Image I/O. Only the required
 * properties cross the interface; embedded text, profiles and raw Tika metadata never do.
 */
public final class TikaMediaInspection implements MediaInspection {
  private final int maxBytes;
  private final int maxPixels;
  private final int maxDimension;
  private final Semaphore capacity;
  private final MimeTypes detector = MimeTypes.getDefaultMimeTypes();

  /** Validates deployment limits at construction; all calls share the same fail-fast capacity. */
  public TikaMediaInspection(int maxBytes, int maxPixels, int maxDimension, int maxConcurrent) {
    if (maxBytes < 1) {
      throw new IllegalArgumentException(
          "plowshare.images.max-bytes must be positive for inspection");
    }
    if (maxPixels < 1 || maxPixels > 64_000_000) {
      throw new IllegalArgumentException("inspection max-pixels must be between 1 and 64000000");
    }
    if (maxDimension < 1 || maxDimension > 32_768) {
      throw new IllegalArgumentException("inspection max-dimension must be between 1 and 32768");
    }
    if (maxConcurrent < 1 || maxConcurrent > 8) {
      throw new IllegalArgumentException("inspection max-concurrent must be between 1 and 8");
    }
    this.maxBytes = maxBytes;
    this.maxPixels = maxPixels;
    this.maxDimension = maxDimension;
    capacity = new Semaphore(maxConcurrent);
  }

  @Override
  public ImageDescriptor inspectImage(byte[] bytes) {
    if (bytes == null || bytes.length == 0) throw new MediaInspectionException(EMPTY);
    if (bytes.length > maxBytes) throw new MediaInspectionException(TOO_LARGE);
    if (!capacity.tryAcquire()) throw new MediaInspectionException(BUSY);
    try {
      // Copy only after admission: parsing and digest must observe the same immutable snapshot.
      byte[] snapshot = bytes.clone();
      ImageFormat format = ImageFormat.of(snapshot);
      try (var prefix =
          TikaInputStream.get(Arrays.copyOf(snapshot, Math.min(8192, snapshot.length)))) {
        String mime = detector.detect(prefix, new Metadata(), new ParseContext()).toString();
        if (format == null
            || !io.aeyer.plowshare.protocol.ImageAdmissions.supportedMimeType(mime)
            || !format.mediaType().equals(mime)) {
          throw new MediaInspectionException(UNSUPPORTED_FORMAT);
        }
      }
      if (format == ImageFormat.PNG) {
        PngValidation png = PngValidation.validate(snapshot, maxPixels, maxDimension);
        decode(png.decodingStream(snapshot), png.width(), png.height(), format);
        return new ImageDescriptor(
            format, png.width(), png.height(), snapshot.length, digest(snapshot));
      }
      StillImageValidation image =
          format == ImageFormat.JPEG
              ? StillImageValidation.jpeg(snapshot, maxPixels, maxDimension)
              : StillImageValidation.webp(snapshot, maxPixels, maxDimension);
      decode(
          new ByteArrayInputStream(image.decodingBytes()), image.width(), image.height(), format);
      return new ImageDescriptor(
          format, image.width(), image.height(), snapshot.length, digest(snapshot));
    } catch (IOException | IndexOutOfBoundsException invalid) {
      // No parser exception cause: it can contain private paths or untrusted embedded metadata.
      throw new MediaInspectionException(INVALID_IMAGE);
    } finally {
      capacity.release();
    }
  }

  private static void decode(InputStream bytes, int width, int height, ImageFormat format)
      throws IOException {
    ImageReader reader = imageReader(format);
    try (var input = new MemoryCacheImageInputStream(bytes)) {
      // Memory-backed stream avoids ImageIO's process-global disk-cache setting. Ignoring metadata
      // is additional defense; the validation view excludes ancillary metadata entirely.
      reader.setInput(input, false, true);
      reader.addIIOReadWarningListener(
          (source, warning) -> {
            throw new MediaInspectionException(INVALID_IMAGE);
          });
      if (reader.getWidth(0) != width || reader.getHeight(0) != height) {
        throw new MediaInspectionException(INVALID_IMAGE);
      }
      BufferedImage decoded = reader.read(0);
      try {
        if (decoded.getWidth() != width || decoded.getHeight() != height) {
          throw new MediaInspectionException(INVALID_IMAGE);
        }
      } finally {
        decoded.flush();
      }
    } finally {
      reader.dispose();
    }
  }

  private static ImageReader imageReader(ImageFormat format) {
    String expected =
        switch (format) {
          case PNG -> "com.sun.imageio.plugins.png.PNGImageReader";
          case JPEG -> "com.sun.imageio.plugins.jpeg.JPEGImageReader";
          case WEBP -> "com.twelvemonkeys.imageio.plugins.webp.WebPImageReader";
          default -> throw new MediaInspectionException(UNSUPPORTED_FORMAT);
        };
    Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(format.declared());
    while (readers.hasNext()) {
      ImageReader reader = readers.next();
      if (reader.getClass().getName().equals(expected)) {
        return reader;
      }
      reader.dispose();
    }
    throw new IllegalStateException(
        "media inspection requires its allowlisted " + format + " ImageReader");
  }

  private static String digest(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
    }
  }
}
