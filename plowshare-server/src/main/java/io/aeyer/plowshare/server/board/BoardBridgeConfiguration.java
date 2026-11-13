package io.aeyer.plowshare.server.board;

import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.relay.BoardRelayReceiver;
import io.aeyer.plowshare.server.relay.Relay;
import io.aeyer.plowshare.server.relay.RelayForwardingHistory;
import io.aeyer.plowshare.server.relay.RelayPorts;
import io.aeyer.plowshare.server.relay.RelayProperties;
import io.aeyer.plowshare.server.relay.RelayPublisherAccounts;
import io.aeyer.plowshare.server.relay.RelayReceivers;
import io.aeyer.plowshare.server.relay.RelayWorkerProperties;
import java.time.Instant;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Composition of forum-owned effects with Relay; interfaces keep the broker out of forum policy.
 */
@Configuration
public class BoardBridgeConfiguration {
  @Bean
  public BoardClosing boardClosing(
      BoardForum board,
      BoardRecords records,
      BoardPostRepository receipts,
      ProjectMembers members,
      ProjectWorkspaces projects,
      UnitOfWork work) {
    return new ProjectBoardClosing(board, records, receipts, members, projects, work);
  }

  @Bean
  @DependsOnDatabaseInitialization
  public BoardBridgeRepository boardBridgeRepository(JdbcTemplate jdbc) {
    return new JdbcBoardBridgeRepository(jdbc);
  }

  @Bean
  public BoardBridge boardBridge(
      BoardForum board,
      BoardRecords records,
      BoardPostRepository receipts,
      BoardBridgeRepository replies,
      BoardClosing closing,
      SwarmCatalog swarms,
      Relay relay,
      UnitOfWork work) {
    return new ForumBoardBridge(board, records, receipts, replies, closing, swarms, relay, work);
  }

  @Bean
  public BoardReplyPublications boardReplyPublications(
      BoardBridgeRepository replies,
      Relay relay,
      UnitOfWork work,
      RelayWorkerProperties properties) {
    return new RelayBoardReplies(
        replies, relay, work, properties.getFailureBackoff(), Instant::now);
  }

  @Bean(destroyMethod = "close")
  public BoardReplyWorker boardReplyWorker(
      BoardReplyPublications replies, RelayWorkerProperties properties) {
    return new BoardReplyWorker(replies, properties);
  }

  @Bean
  public RelayReceivers.Binding boardOpenReceiver(
      @Lazy BoardBridge bridge,
      RelayPublisherAccounts publishers,
      RelayPorts ports,
      RelayForwardingHistory history,
      RelayProperties properties) {
    return receiver("board.open", bridge, publishers, ports, history, properties);
  }

  @Bean
  public RelayReceivers.Binding boardPostReceiver(
      @Lazy BoardBridge bridge,
      RelayPublisherAccounts publishers,
      RelayPorts ports,
      RelayForwardingHistory history,
      RelayProperties properties) {
    return receiver("board.post", bridge, publishers, ports, history, properties);
  }

  @Bean
  public RelayReceivers.Binding boardCloseReceiver(
      @Lazy BoardBridge bridge,
      RelayPublisherAccounts publishers,
      RelayPorts ports,
      RelayForwardingHistory history,
      RelayProperties properties) {
    return receiver("board.close", bridge, publishers, ports, history, properties);
  }

  private static RelayReceivers.Binding receiver(
      String name,
      BoardBridge bridge,
      RelayPublisherAccounts publishers,
      RelayPorts ports,
      RelayForwardingHistory history,
      RelayProperties properties) {
    return new RelayReceivers.Binding(
        name,
        new BoardRelayReceiver(
            name, bridge, publishers, ports, history, properties.getMaxForwardingHops()));
  }
}
