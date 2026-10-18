package io.aeyer.plowshare.server.embedding;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.llm.EmbeddingClient;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** Provider IO occurs after source capture and before the short, version-fenced publication. */
@Component
public final class BoundEmbeddingRepairs implements EmbeddingRepairs {
  private final EmbeddingWorkRepository records;
  private final OperationEmbeddings operations;
  private final EmbeddingClient client;
  private final Supplier<DualEmbeddings> dual;

  @org.springframework.beans.factory.annotation.Autowired
  public BoundEmbeddingRepairs(
      EmbeddingWorkRepository records,
      OperationEmbeddings operations,
      EmbeddingClient client,
      org.springframework.beans.factory.ObjectProvider<DualEmbeddings> dual) {
    this(records, operations, client, dual::getIfAvailable);
  }

  public BoundEmbeddingRepairs(
      EmbeddingWorkRepository records,
      OperationEmbeddings operations,
      EmbeddingClient client,
      Supplier<DualEmbeddings> dual) {
    this.records = Objects.requireNonNull(records);
    this.operations = Objects.requireNonNull(operations);
    this.client = Objects.requireNonNull(client);
    this.dual = Objects.requireNonNull(dual);
  }

  public boolean repair(EmbeddingWorkRepository.Key key, Budget budget, Runnable validity) {
    validity.run();
    var captured = records.source(key);
    if (captured.isEmpty()) return false;
    var source = captured.get();
    Home home = source.project() == null ? Home.global() : Home.of(source.project());
    try (var permit = operations.publication(home, List.of(source), budget, validity)) {
      var encoders = dual.get();
      var owner =
          UsageAttribution.system(source.project(), UsageAttribution.Operation.EMBEDDING_REPAIR);
      if (encoders != null) return encoders.forWrite(permit).repair(key, owner);
      var vector = client.forOperation(permit).embed(source.text(), owner);
      return records.publishLegacy(source, vector, permit::publicationFence);
    }
  }
}
