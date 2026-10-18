package io.aeyer.plowshare.server.embedding;

import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Length-delimited immutable evidence; commas and brackets in inputs cannot alias batch shapes. */
final class EmbeddingEvidence {
  private EmbeddingEvidence() {}

  static String digest(List<String> fields) {
    try {
      var hash = MessageDigest.getInstance("SHA-256");
      for (String field : fields) {
        byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
        hash.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        hash.update(bytes);
      }
      return HexFormat.of().formatHex(hash.digest());
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  static String task(EmbeddingPermit.Task task) {
    var fields = new ArrayList<String>();
    fields.add(task.mode().name());
    fields.add(task.profile());
    fields.add(task.request().specifier());
    fields.addAll(task.request().input());
    return digest(fields);
  }
}
