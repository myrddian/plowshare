package io.aeyer.plowshare.server.fetch;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.OptionalInt;
import javax.net.SocketFactory;

/**
 * The sockets fetch dials through. Each one asks {@link AddressPolicy} about the concrete address
 * it is about to connect to, and refuses it with {@link RefusedAddress} before any packet is sent
 * (spec §2.1).
 *
 * <p>The check sits here, not in a custom {@code Dns} or on the URL string, because this is the one
 * place every spelling of an address arrives concretely: a name that resolves privately, a
 * rebinding between an earlier check and this connect, an IP literal in any form the URL parsers
 * accept, and an IPv4-mapped IPv6 address. OkHttp 4.12 skips {@code Dns} entirely for an IP-literal
 * host, so a {@code Dns} guard would miss exactly the literal case. OkHttp layers TLS over the
 * plain socket this factory makes, so {@code https} passes through the same check.
 *
 * <p>An instance is strict, or exempts one port. {@code FetchConfig} builds one strict factory and
 * one exempting factory per {@code plowshare.fetch.allow-private} entry, once, at boot. The
 * exemption belongs to the factory, so a socket knows it from the client that made it, and nothing
 * is threaded through per call. An exemption covers only the private tier.
 *
 * <p>An endpoint the socket cannot judge at all — anything other than a resolved {@link
 * InetSocketAddress} — is refused as {@link AddressPolicy.Tier#NEVER}, before {@link
 * AddressPolicy#judge} is even called. {@link AddressPolicy#tierOf} is not defensive against a null
 * address, and an unresolved {@link InetSocketAddress} carries one; treating "cannot judge" as the
 * strictest tier is also the only sound default, since no allowlist entry may exempt what was never
 * judged in the first place.
 */
final class GuardedSockets extends SocketFactory {

  private final OptionalInt exemptPort;

  private GuardedSockets(OptionalInt exemptPort) {
    this.exemptPort = exemptPort;
  }

  /** Refuses every private and never address. */
  static GuardedSockets strict() {
    return new GuardedSockets(OptionalInt.empty());
  }

  /** Also connects to a private address, but only on {@code port}. */
  static GuardedSockets exempting(int port) {
    return new GuardedSockets(OptionalInt.of(port));
  }

  /** The one OkHttp calls: an unconnected socket, which it then {@code connect}s. */
  @Override
  public Socket createSocket() {
    return new GuardedSocket(exemptPort);
  }

  @Override
  public Socket createSocket(String host, int port) throws IOException {
    return connected(new InetSocketAddress(host, port), null);
  }

  @Override
  public Socket createSocket(String host, int port, InetAddress localHost, int localPort)
      throws IOException {
    return connected(
        new InetSocketAddress(host, port), new InetSocketAddress(localHost, localPort));
  }

  @Override
  public Socket createSocket(InetAddress host, int port) throws IOException {
    return connected(new InetSocketAddress(host, port), null);
  }

  @Override
  public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
      throws IOException {
    return connected(
        new InetSocketAddress(address, port), new InetSocketAddress(localAddress, localPort));
  }

  private Socket connected(InetSocketAddress endpoint, InetSocketAddress local) throws IOException {
    Socket socket = createSocket();
    try {
      if (local != null) {
        socket.bind(local);
      }
      socket.connect(endpoint);
      return socket;
    } catch (IOException refusedOrFailed) {
      socket.close();
      throw refusedOrFailed;
    }
  }

  /** A plain socket whose connect asks {@link AddressPolicy} first. */
  static final class GuardedSocket extends Socket {

    private final OptionalInt exemptPort;

    GuardedSocket(OptionalInt exemptPort) {
      this.exemptPort = exemptPort;
    }

    @Override
    public void connect(SocketAddress endpoint, int timeout) throws IOException {
      if (!(endpoint instanceof InetSocketAddress inet)
          || inet.isUnresolved()
          || inet.getAddress() == null) {
        throw new RefusedAddress(AddressPolicy.Tier.NEVER);
      }
      AddressPolicy.Verdict verdict =
          AddressPolicy.judge(inet.getAddress(), inet.getPort(), exemptPort);
      if (!verdict.allowed()) {
        throw new RefusedAddress(verdict.tier());
      }
      super.connect(endpoint, timeout);
    }
  }
}
