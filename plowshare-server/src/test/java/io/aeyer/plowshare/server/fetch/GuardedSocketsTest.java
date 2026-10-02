package io.aeyer.plowshare.server.fetch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import org.junit.jupiter.api.Test;

/**
 * The connect-time check on a real loopback socket. A refused connect must throw
 * {@link RefusedAddress} rather than {@code ConnectException} or {@code
 * UnknownHostException}. That type is what tells a refusal from a dead host
 * further up.
 */
class GuardedSocketsTest {

    private static ServerSocket loopbackServer() throws IOException {
        return new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
    }

    private static InetSocketAddress endpointOf(ServerSocket server) {
        return new InetSocketAddress(server.getInetAddress(), server.getLocalPort());
    }

    @Test
    void a_strict_socket_refuses_loopback_before_it_connects() throws IOException {
        try (ServerSocket server = loopbackServer();
                Socket socket = GuardedSockets.strict().createSocket()) {
            RefusedAddress refused = assertThrows(RefusedAddress.class,
                    () -> socket.connect(endpointOf(server), 1000));
            assertEquals(AddressPolicy.Tier.PRIVATE, refused.tier());
            assertFalse(socket.isConnected());
            assertFalse(refused.getMessage().contains("127.0.0.1"),
                    "a refusal never names the address: " + refused.getMessage());
        }
    }

    @Test
    void the_one_argument_connect_is_guarded_too() throws IOException {
        try (ServerSocket server = loopbackServer();
                Socket socket = GuardedSockets.strict().createSocket()) {
            assertThrows(RefusedAddress.class, () -> socket.connect(endpointOf(server)));
        }
    }

    @Test
    void an_exempted_port_connects_to_a_private_address() throws IOException {
        try (ServerSocket server = loopbackServer();
                Socket socket = GuardedSockets.exempting(server.getLocalPort()).createSocket()) {
            socket.connect(endpointOf(server), 1000);
            assertTrue(socket.isConnected());
        }
    }

    @Test
    void an_exemption_covers_its_own_port_only() throws IOException {
        try (ServerSocket server = loopbackServer()) {
            int otherPort = server.getLocalPort() == 65535 ? 65534 : server.getLocalPort() + 1;
            try (Socket socket = GuardedSockets.exempting(otherPort).createSocket()) {
                assertThrows(RefusedAddress.class, () -> socket.connect(endpointOf(server), 1000));
            }
        }
    }

    @Test
    void the_never_tier_is_refused_on_an_exempted_port() throws IOException {
        InetSocketAddress metadata =
                new InetSocketAddress(InetAddress.getByName("169.254.169.254"), 80);
        try (Socket socket = GuardedSockets.exempting(80).createSocket()) {
            RefusedAddress refused =
                    assertThrows(RefusedAddress.class, () -> socket.connect(metadata, 1000));
            assertEquals(AddressPolicy.Tier.NEVER, refused.tier());
        }
    }

    @Test
    void an_unresolved_endpoint_is_refused() throws IOException {
        try (Socket socket = GuardedSockets.strict().createSocket()) {
            assertThrows(RefusedAddress.class, () -> socket.connect(
                    InetSocketAddress.createUnresolved("intranet.example", 80), 1000));
        }
    }

    @Test
    void an_unresolved_endpoint_is_refused_as_never() throws IOException {
        try (Socket socket = GuardedSockets.strict().createSocket()) {
            RefusedAddress refused = assertThrows(RefusedAddress.class, () -> socket.connect(
                    InetSocketAddress.createUnresolved("intranet.example", 80), 1000));
            assertEquals(AddressPolicy.Tier.NEVER, refused.tier());
        }
    }

    @Test
    void the_connecting_factory_methods_are_guarded_too() throws IOException {
        try (ServerSocket server = loopbackServer()) {
            assertThrows(RefusedAddress.class, () -> GuardedSockets.strict()
                    .createSocket(server.getInetAddress(), server.getLocalPort()));
            assertThrows(RefusedAddress.class, () -> GuardedSockets.strict()
                    .createSocket("127.0.0.1", server.getLocalPort()));
        }
    }
}
