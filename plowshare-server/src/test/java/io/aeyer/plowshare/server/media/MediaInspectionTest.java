package io.aeyer.plowshare.server.media;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.ImageFormat;
import io.aeyer.plowshare.server.images.ImageStore;
import io.aeyer.plowshare.server.media.MediaInspectionException.Reason;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.apache.tika.parser.Parser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Real encoded images and adversarial bytes; no endpoint, database, disk cache or document parser.
 */
class MediaInspectionTest {
  private final MediaInspection inspection =
      new TikaMediaInspection(5 * 1024 * 1024, 16_000_000, 16_384, 2);

  @Test
  void descriptor_covers_original_bytes_and_reuses_existing_image_identity(@TempDir Path root)
      throws Exception {
    byte[] bytes = png(17, 9, BufferedImage.TYPE_INT_ARGB, false);
    byte[] original = bytes.clone();
    ImageDescriptor descriptor = inspection.inspectImage(bytes);
    assertEquals("image/png", descriptor.mimeType());
    assertEquals(ImageFormat.PNG, descriptor.format());
    assertEquals(17, descriptor.width());
    assertEquals(9, descriptor.height());
    assertEquals(bytes.length, descriptor.byteLength());
    assertEquals(
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
        descriptor.sha256());
    assertEquals("img_" + descriptor.sha256().substring(0, 32), ImageStore.idFor(bytes));
    ImageStore store = new ImageStore(home -> root.resolve(home.project()), 4096);
    var image = store.store(Home.of("atlas"), "caller-name-is-not-used.pdf", bytes);
    assertEquals(ImageStore.idFor(bytes), image.id());
    assertArrayEquals(bytes, Files.readAllBytes(root.resolve("atlas").resolve(image.fileName())));
    assertArrayEquals(original, bytes);
    Arrays.fill(bytes, (byte) 0);
    assertEquals(17, descriptor.width()); // No mutable byte payload is retained in the descriptor.
  }

  @ParameterizedTest
  @ValueSource(
      ints = {
        BufferedImage.TYPE_INT_RGB,
        BufferedImage.TYPE_INT_ARGB,
        BufferedImage.TYPE_BYTE_GRAY,
        BufferedImage.TYPE_USHORT_GRAY,
        BufferedImage.TYPE_BYTE_BINARY,
        BufferedImage.TYPE_BYTE_INDEXED
      })
  void validates_real_png_color_depths_and_adam7_interlacing(int type) throws IOException {
    for (boolean interlaced : List.of(false, true)) {
      ImageDescriptor image = inspection.inspectImage(png(13, 7, type, interlaced));
      assertEquals(13, image.width());
      assertEquals(7, image.height());
    }
  }

  @Test
  void single_pixel_interlaced_image_has_empty_adam7_passes() throws IOException {
    assertEquals(1, inspection.inspectImage(png(1, 1, BufferedImage.TYPE_INT_ARGB, true)).width());
  }

  @Test
  void empty_byte_limit_and_unsupported_formats_are_distinct() throws IOException {
    assertReason(Reason.EMPTY, null);
    assertReason(Reason.EMPTY, new byte[0]);
    var tiny = new TikaMediaInspection(8, 1, 1, 1);
    assertEquals(
        Reason.TOO_LARGE,
        assertThrows(
                MediaInspectionException.class,
                () -> tiny.inspectImage(png(1, 1, BufferedImage.TYPE_INT_RGB, false)))
            .reason());
    for (byte[] unsupported :
        List.of(
            "GIF89a".getBytes(StandardCharsets.US_ASCII),
            "<svg xmlns='http://www.w3.org/2000/svg'/>".getBytes(StandardCharsets.UTF_8),
            "%PDF-1.7".getBytes(StandardCharsets.US_ASCII))) {
      assertReason(Reason.UNSUPPORTED_FORMAT, unsupported);
    }
  }

  @Test
  void every_truncation_is_refused_and_capacity_is_released() throws IOException {
    byte[] bytes = png(5, 3, BufferedImage.TYPE_INT_ARGB, false);
    var single = new TikaMediaInspection(4096, 100, 100, 1);
    for (int end = 0; end < bytes.length; end++) {
      byte[] truncated = Arrays.copyOf(bytes, end);
      assertThrows(MediaInspectionException.class, () -> single.inspectImage(truncated));
    }
    assertEquals(5, single.inspectImage(bytes).width());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidPngs")
  void rejects_crc_ordering_unknown_critical_and_zlib_failures(String label, byte[] bytes) {
    assertReason(Reason.INVALID_IMAGE, bytes);
  }

  static Stream<Arguments> invalidPngs() throws IOException {
    List<Part> good = parts(png(1, 1, BufferedImage.TYPE_INT_RGB, false));
    Part header = good.getFirst();
    Part data = good.stream().filter(p -> p.type().equals("IDAT")).findFirst().orElseThrow();
    Part end = new Part("IEND", new byte[0]);
    byte[] crc = encode(good);
    crc[29] ^= 1;
    return Stream.of(
        Arguments.of("bad CRC", crc),
        Arguments.of("missing header", encode(List.of(data, end))),
        Arguments.of("duplicate header", encode(List.of(header, header, data, end))),
        Arguments.of(
            "unknown critical chunk",
            encode(List.of(header, new Part("JUNK", new byte[0]), data, end))),
        Arguments.of(
            "reserved chunk bit",
            encode(List.of(header, new Part("teat", new byte[0]), data, end))),
        Arguments.of(
            "invalid chunk name",
            encode(List.of(header, new Part("t!Xt", new byte[0]), data, end))),
        Arguments.of("missing IDAT", encode(List.of(header, end))),
        Arguments.of(
            "nonconsecutive IDAT",
            encode(List.of(header, data, new Part("tEXt", new byte[0]), data, end))),
        Arguments.of("duplicate IEND", encode(List.of(header, data, end, end))),
        Arguments.of("trailing bytes", concat(encode(good), new byte[] {1})),
        Arguments.of(
            "invalid deflate",
            encode(List.of(header, new Part("IDAT", new byte[] {1, 2, 3}), end))),
        Arguments.of(
            "truncated zlib trailer",
            encode(
                List.of(
                    header,
                    new Part("IDAT", Arrays.copyOf(data.data(), data.data().length - 1)),
                    end))),
        Arguments.of(
            "extra compressed bytes",
            encode(List.of(header, new Part("IDAT", concat(data.data(), new byte[] {0})), end))),
        Arguments.of(
            "too few scanline bytes",
            encode(List.of(header, new Part("IDAT", deflate(new byte[] {0, 1})), end))),
        Arguments.of(
            "too many scanline bytes",
            encode(List.of(header, new Part("IDAT", deflate(new byte[100_000])), end))),
        Arguments.of(
            "invalid row filter",
            encode(List.of(header, new Part("IDAT", deflate(new byte[] {5, 1, 2, 3})), end))),
        Arguments.of("trailing IDAT stream", encode(List.of(header, data, data, end))),
        Arguments.of(
            "transparency after pixels",
            encode(List.of(header, data, new Part("tRNS", new byte[6]), end))),
        Arguments.of(
            "duplicate palette",
            encode(
                List.of(
                    header,
                    new Part("PLTE", new byte[3]),
                    new Part("PLTE", new byte[3]),
                    data,
                    end))));
  }

  @ParameterizedTest
  @ValueSource(strings = {"acTL", "fcTL", "fdAT"})
  void refuses_apng_even_when_a_normal_decoder_would_only_show_the_first_frame(String type)
      throws IOException {
    var chunks = parts(png(1, 1, BufferedImage.TYPE_INT_RGB, false));
    chunks.add(1, new Part(type, new byte[8]));
    assertReason(Reason.ANIMATION, encode(chunks));
  }

  @Test
  void dimensions_are_checked_before_decoding_and_products_do_not_overflow() throws IOException {
    byte[] png = png(5, 4, BufferedImage.TYPE_INT_RGB, false);
    var limits = new TikaMediaInspection(4096, 19, 100, 1);
    assertEquals(
        Reason.DIMENSIONS,
        assertThrows(MediaInspectionException.class, () -> limits.inspectImage(png)).reason());
    var chunks = parts(png);
    ByteBuffer.wrap(chunks.getFirst().data()).putInt(Integer.MAX_VALUE).putInt(Integer.MAX_VALUE);
    assertReason(Reason.DIMENSIONS, encode(chunks));
    ByteBuffer.wrap(chunks.getFirst().data()).putInt(0).putInt(1);
    assertReason(Reason.INVALID_IMAGE, encode(chunks));
  }

  @Test
  void compressed_metadata_is_opaque_but_included_in_the_original_digest() throws IOException {
    byte[] original = png(3, 2, BufferedImage.TYPE_INT_ARGB, false);
    var chunks = parts(original);
    byte[] compressedText = concat(new byte[] {'k', 0, 0}, deflate(new byte[8_000_000]));
    chunks.add(1, new Part("zTXt", compressedText));
    byte[] withMetadata = encode(chunks);
    ImageDescriptor image = inspection.inspectImage(withMetadata);
    assertEquals(3, image.width());
    assertEquals(withMetadata.length, image.byteLength());
    assertNotEquals(inspection.inspectImage(original).sha256(), image.sha256());
  }

  @Test
  void many_empty_chunks_are_bounded() throws IOException {
    var chunks = parts(png(1, 1, BufferedImage.TYPE_INT_RGB, false));
    for (int n = 0; n < 1024; n++) chunks.add(1, new Part("tEXt", new byte[0]));
    assertReason(Reason.TOO_COMPLEX, encode(chunks));
  }

  @Test
  void consecutive_idat_chunks_can_split_anywhere_in_the_zlib_stream() throws IOException {
    var chunks = parts(png(7, 2, BufferedImage.TYPE_INT_ARGB, false));
    Part data = chunks.remove(1);
    for (int i = data.data().length - 1; i >= 0; i--) {
      chunks.add(1, new Part("IDAT", new byte[] {data.data()[i]}));
    }
    assertEquals(7, inspection.inspectImage(encode(chunks)).width());
  }

  @Test
  void concurrent_calls_refuse_excess_work_without_queuing_or_leaking_permits() throws Exception {
    byte[] large = png(2000, 2000, BufferedImage.TYPE_INT_ARGB, false);
    var single = new TikaMediaInspection(large.length, 4_000_000, 2000, 1);
    var start = new CyclicBarrier(12);
    try (var threads = Executors.newFixedThreadPool(12)) {
      var calls = new ArrayList<Callable<Boolean>>();
      for (int i = 0; i < 12; i++) {
        calls.add(
            () -> {
              start.await(10, TimeUnit.SECONDS);
              try {
                single.inspectImage(large);
                return true;
              } catch (MediaInspectionException refused) {
                assertEquals(Reason.BUSY, refused.reason());
                return false;
              }
            });
      }
      var results = threads.invokeAll(calls);
      int admitted = 0;
      for (var result : results) if (result.get(10, TimeUnit.SECONDS)) admitted++;
      assertTrue(admitted > 0);
      assertTrue(admitted < 12);
    }
    assertEquals(2000, single.inspectImage(large).width());
  }

  @Test
  void no_tika_parser_modules_are_discovered() {
    assertFalse(ServiceLoader.load(Parser.class).iterator().hasNext());
  }

  @Test
  void descriptor_rejects_invalid_properties() {
    String hash = "0".repeat(64);
    assertThrows(NullPointerException.class, () -> new ImageDescriptor(null, 1, 1, 1, hash));
    assertThrows(
        IllegalArgumentException.class, () -> new ImageDescriptor(ImageFormat.PNG, 0, 1, 1, hash));
    assertThrows(
        IllegalArgumentException.class, () -> new ImageDescriptor(ImageFormat.PNG, 1, -1, 1, hash));
    assertThrows(
        IllegalArgumentException.class, () -> new ImageDescriptor(ImageFormat.PNG, 1, 1, 0, hash));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ImageDescriptor(ImageFormat.PNG, 1, 1, 1, "private-payload"));
  }

  @Test
  void refusals_do_not_expose_embedded_text_or_parser_causes() throws IOException {
    var chunks = parts(png(1, 1, BufferedImage.TYPE_INT_RGB, false));
    chunks.add(1, new Part("JUNK", "sensitive-content".getBytes(StandardCharsets.UTF_8)));
    var failure =
        assertThrows(MediaInspectionException.class, () -> inspection.inspectImage(encode(chunks)));
    assertFalse(failure.toString().contains("sensitive-content"));
    assertEquals(null, failure.getCause());
  }

  private void assertReason(Reason reason, byte[] bytes) {
    assertEquals(
        reason,
        assertThrows(MediaInspectionException.class, () -> inspection.inspectImage(bytes))
            .reason());
  }

  static byte[] png(int width, int height, int type, boolean interlace) throws IOException {
    BufferedImage image = new BufferedImage(width, height, type);
    image.setRGB(0, 0, 0xff123456);
    var writer = ImageIO.getImageWritersByFormatName("png").next();
    var bytes = new ByteArrayOutputStream();
    try (var output = new MemoryCacheImageOutputStream(bytes)) {
      writer.setOutput(output);
      var param = writer.getDefaultWriteParam();
      param.setProgressiveMode(
          interlace ? ImageWriteParam.MODE_DEFAULT : ImageWriteParam.MODE_DISABLED);
      writer.write(null, new javax.imageio.IIOImage(image, null, null), param);
    } finally {
      writer.dispose();
      image.flush();
    }
    return bytes.toByteArray();
  }

  private record Part(String type, byte[] data) {}

  private static List<Part> parts(byte[] png) {
    var result = new ArrayList<Part>();
    ByteBuffer input = ByteBuffer.wrap(png);
    input.position(8);
    while (input.hasRemaining()) {
      int length = input.getInt();
      byte[] type = new byte[4];
      input.get(type);
      byte[] data = new byte[length];
      input.get(data);
      input.getInt();
      result.add(new Part(new String(type, StandardCharsets.US_ASCII), data));
    }
    return result;
  }

  private static byte[] encode(List<Part> parts) throws IOException {
    var bytes = new ByteArrayOutputStream();
    var output = new DataOutputStream(bytes);
    output.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10});
    for (Part part : parts) {
      byte[] type = part.type().getBytes(StandardCharsets.US_ASCII);
      output.writeInt(part.data().length);
      output.write(type);
      output.write(part.data());
      CRC32 crc = new CRC32();
      crc.update(type);
      crc.update(part.data());
      output.writeInt((int) crc.getValue());
    }
    return bytes.toByteArray();
  }

  private static byte[] deflate(byte[] bytes) throws IOException {
    var result = new ByteArrayOutputStream();
    try (var compressed = new DeflaterOutputStream(result)) {
      compressed.write(bytes);
    }
    return result.toByteArray();
  }

  private static byte[] concat(byte[] first, byte[] second) {
    byte[] result = Arrays.copyOf(first, first.length + second.length);
    System.arraycopy(second, 0, result, first.length, second.length);
    return result;
  }
}
