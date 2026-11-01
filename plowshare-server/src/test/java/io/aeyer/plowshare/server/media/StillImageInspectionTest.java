package io.aeyer.plowshare.server.media;

import static io.aeyer.plowshare.server.media.MediaInspectionException.Reason.ANIMATION;
import static io.aeyer.plowshare.server.media.MediaInspectionException.Reason.DIMENSIONS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StillImageInspectionTest {
  private final MediaInspection inspection = new TikaMediaInspection(4096, 100, 20, 1);

  @ParameterizedTest
  @ValueSource(strings = {"still-lossy.webp", "still-lossless.webp", "still-alpha.webp"})
  void webp_pixels_are_decoded_and_original_identity_is_preserved(String name) throws Exception {
    byte[] bytes = resource(name);
    var result = inspection.inspectImage(bytes);
    assertEquals("image/webp", result.mimeType());
    assertEquals(3, result.width());
    assertEquals(2, result.height());
    assertEquals(bytes.length, result.byteLength());
    assertEquals(
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
        result.sha256());
    for (int length = 0; length < bytes.length; length++) {
      byte[] cut = Arrays.copyOf(bytes, length);
      assertThrows(MediaInspectionException.class, () -> inspection.inspectImage(cut));
    }
    byte[] trailing = Arrays.copyOf(bytes, bytes.length + 2);
    assertThrows(MediaInspectionException.class, () -> inspection.inspectImage(trailing));
    var tiny = new TikaMediaInspection(4096, 5, 20, 1);
    assertEquals(
        DIMENSIONS,
        assertThrows(MediaInspectionException.class, () -> tiny.inspectImage(bytes)).reason());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void jpeg_baseline_and_progressive_are_validated_without_reencoding(boolean progressive)
      throws Exception {
    byte[] bytes = jpeg(progressive);
    var descriptor = inspection.inspectImage(bytes);
    assertEquals("image/jpeg", descriptor.mimeType());
    assertEquals(3, descriptor.width());
    assertEquals(2, descriptor.height());
    assertEquals(bytes.length, descriptor.byteLength());
    for (int length = 0; length < bytes.length; length++) {
      byte[] cut = Arrays.copyOf(bytes, length);
      assertThrows(MediaInspectionException.class, () -> inspection.inspectImage(cut));
    }
    var joined = new ByteArrayOutputStream();
    joined.writeBytes(bytes);
    joined.writeBytes(bytes);
    assertThrows(
        MediaInspectionException.class, () -> inspection.inspectImage(joined.toByteArray()));
    var tiny = new TikaMediaInspection(4096, 5, 20, 1);
    assertEquals(
        DIMENSIONS,
        assertThrows(MediaInspectionException.class, () -> tiny.inspectImage(bytes)).reason());
  }

  @Test
  void compressed_and_raw_alpha_are_validated_without_dropping_original_bytes() throws Exception {
    byte[] compressed = resource("still-compressed-alpha.webp");
    var descriptor = inspection.inspectImage(compressed);
    assertEquals(3, descriptor.width());
    assertEquals(20, descriptor.height());
    assertEquals(compressed.length, descriptor.byteLength());
    byte[] invalidRaw = resource("still-alpha.webp");
    // The original raw ALPH plane must have exactly canvasWidth * canvasHeight samples.
    invalidRaw[24] = 3; // Grow canvas width while leaving alpha data unchanged.
    assertThrows(MediaInspectionException.class, () -> inspection.inspectImage(invalidRaw));
  }

  @Test
  void animation_forged_container_and_invalid_pixel_payload_are_refused() throws Exception {
    assertEquals(
        ANIMATION,
        assertThrows(
                MediaInspectionException.class,
                () -> inspection.inspectImage(resource("animated.webp")))
            .reason());
    byte[] invalid = Arrays.copyOf(resource("still-lossless.webp"), 26);
    ByteBuffer.wrap(invalid).order(ByteOrder.LITTLE_ENDIAN).putInt(4, 18).putInt(16, 5);
    assertThrows(MediaInspectionException.class, () -> inspection.inspectImage(invalid));
    byte[] oversized = resource("still-alpha.webp");
    Arrays.fill(oversized, 24, 30, (byte) 0xff);
    assertEquals(
        DIMENSIONS,
        assertThrows(MediaInspectionException.class, () -> inspection.inspectImage(oversized))
            .reason());
    byte[] overflow = resource("still-lossless.webp");
    ByteBuffer.wrap(overflow).order(ByteOrder.LITTLE_ENDIAN).putInt(16, -1);
    assertThrows(MediaInspectionException.class, () -> inspection.inspectImage(overflow));
    assertEquals(3, inspection.inspectImage(resource("still-lossy.webp")).width());
  }

  static byte[] resource(String name) throws Exception {
    try (var input =
        StillImageInspectionTest.class.getResourceAsStream("/media/" + name + ".base64")) {
      assertNotNull(input);
      return java.util.Base64.getMimeDecoder().decode(input.readAllBytes());
    }
  }

  static byte[] jpeg(boolean progressive) throws Exception {
    var image = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
    var writer = ImageIO.getImageWritersByFormatName("jpeg").next();
    var bytes = new ByteArrayOutputStream();
    try (var output = new MemoryCacheImageOutputStream(bytes)) {
      writer.setOutput(output);
      var parameters = writer.getDefaultWriteParam();
      if (progressive) parameters.setProgressiveMode(ImageWriteParam.MODE_DEFAULT);
      writer.write(null, new javax.imageio.IIOImage(image, null, null), parameters);
    } finally {
      writer.dispose();
      image.flush();
    }
    return bytes.toByteArray();
  }
}
