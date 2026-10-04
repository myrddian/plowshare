package io.aeyer.plowshare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.DataInputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Verifies the JDBC driver's actual startup packet, including explicit connection overrides. */
class PostgresTestDefaultsTest {
  @Test
  void local_test_connections_start_postgres_without_an_optional_ssl_probe() throws Exception {
    assertEquals(196608, firstPacket("")); // PostgreSQL protocol version 3.0.
  }

  @Test
  void explicit_ssl_requirements_override_the_local_test_default() throws Exception {
    assertEquals(80877103, firstPacket("?sslmode=require")); // PostgreSQL SSLRequest.
  }

  private static int firstPacket(String options) throws Exception {
    try (var listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      listener.setSoTimeout(5000);
      var packet =
          executor.submit(
              () -> {
                try (var socket = listener.accept();
                    var input = new DataInputStream(socket.getInputStream())) {
                  input.readInt(); // Packet length; the second field identifies the protocol
                  // request.
                  return input.readInt();
                }
              });
      var properties = new Properties();
      properties.setProperty("user", "fixture");
      properties.setProperty("password", "fixture");
      properties.setProperty("connectTimeout", "2");
      properties.setProperty("socketTimeout", "2");
      properties.setProperty("gssencmode", "disable");
      // The probe deliberately closes after the first packet rather than serving a database.
      assertThrows(
          SQLException.class,
          () ->
              DriverManager.getConnection(
                  "jdbc:postgresql://127.0.0.1:" + listener.getLocalPort() + "/fixture" + options,
                  properties));
      return packet.get(5, TimeUnit.SECONDS);
    }
  }
}
