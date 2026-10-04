package io.aeyer.plowshare.server.llm.accounting;

import io.aeyer.plowshare.server.auth.AdminStore;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.llm.LlmProperties;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Durable operator overrides take precedence over boot configuration, without rewriting history.
 */
@Service
public final class ManagedPricing implements PricingAdministration, InferencePrices {
  private final PricingCatalog base;
  private final List<Target> targets;
  private final PricingRepository repository;
  private final AdminStore accounts;
  private final TransactionTemplate changes;
  private final Clock clock;

  private record Target(String route, String model, List<String> pools) {}

  @org.springframework.beans.factory.annotation.Autowired
  public ManagedPricing(
      LlmProperties properties,
      PricingRepository repository,
      AdminStore accounts,
      PlatformTransactionManager transactions) {
    this(properties, repository, accounts, transactions, Clock.systemUTC());
  }

  public ManagedPricing(
      LlmProperties properties,
      PricingRepository repository,
      AdminStore accounts,
      PlatformTransactionManager transactions,
      Clock clock) {
    this.base = PricingCatalog.from(properties);
    this.repository = repository;
    this.accounts = accounts;
    this.changes = new TransactionTemplate(transactions);
    this.clock = clock;
    this.targets =
        properties.getPools().stream()
            .flatMap(
                pool ->
                    pool.getModels().stream()
                        .map(model -> new Target(pool.billingRoute(), model, List.of())))
            .distinct()
            .map(
                target ->
                    new Target(
                        target.route(),
                        target.model(),
                        properties.getPools().stream()
                            .filter(
                                pool ->
                                    pool.billingRoute().equals(target.route())
                                        && pool.getModels().contains(target.model()))
                            .map(pool -> pool.getName())
                            .sorted()
                            .toList()))
            .sorted(java.util.Comparator.comparing(Target::route).thenComparing(Target::model))
            .toList();
  }

  @Override
  public PricingCatalog.Route routeFor(String pool) {
    return base.routeFor(pool);
  }

  @Override
  public Optional<RateCard> select(String route, String model, Instant admission) {
    var override = repository.find(route, model);
    return override.isPresent()
        ? Optional.of(override.get().card())
        : base.select(route, model, admission);
  }

  @Override
  public List<Entry> list(String actor) {
    accounts.requireServerAdmin(actor);
    return targets.stream().map(this::entry).toList();
  }

  private Entry entry(Target target) {
    var override = repository.find(target.route(), target.model());
    var configured = base.cards(target.route(), target.model());
    var card =
        override
            .map(PricingRepository.PriceOverride::card)
            .orElseGet(
                () -> base.select(target.route(), target.model(), clock.instant()).orElse(null));
    // Boot versions include every interval so a deployment change invalidates a stale editor.
    String version =
        override
            .map(row -> row.card().version())
            .orElse(
                "boot:"
                    + String.join(
                        ",", configured.stream().map(RateCard::version).sorted().toList()));
    return new Entry(
        target.route(),
        target.model(),
        target.pools(),
        version,
        override.isPresent() ? "override" : "configuration",
        card == null ? null : view(card),
        configured.stream().map(ManagedPricing::view).toList(),
        override.map(PricingRepository.PriceOverride::updatedAt).orElse(null));
  }

  @Override
  public Entry set(String actor, Change change) {
    accounts.requireServerAdmin(actor);
    if (change == null) throw new CallerFault("Pricing change is required");
    Target target =
        targets.stream()
            .filter(
                t -> t.route().equals(change.billingRoute()) && t.model().equals(change.model()))
            .findFirst()
            .orElseThrow(
                () -> new CallerFault("Pricing needs a served billing route and exact model"));
    if (change.expectedVersion() == null || change.expectedVersion().length() > 65536)
      throw new CallerFault("expectedVersion is required; refresh pricing first");
    final RateCard card;
    try {
      var tiers = change.tiers() == null ? List.<Tier>of() : change.tiers();
      if (tiers.size() > 100)
        throw new IllegalArgumentException("At most 100 price tiers are allowed");
      card =
          new RateCard(
              UUID.randomUUID().toString(),
              target.route(),
              target.model(),
              RateCard.Mode.valueOf(change.mode()),
              change.currency(),
              null,
              null,
              rates(change.rates()),
              tiers.stream()
                  .map(t -> new RateCard.Tier(t.fromInputTokens(), rates(t.rates())))
                  .toList(),
              decimal(change.requestFee()),
              change.source() == null ? "operator" : change.source());
      if (card.mode() == RateCard.Mode.TOKEN
          && (card.rates() == null
              || card.rates().input() == null
              || card.rates().output() == null))
        throw new IllegalArgumentException("Token pricing needs input and output rates");
    } catch (IllegalArgumentException | NullPointerException invalid) {
      throw new CallerFault("Invalid pricing: " + invalid.getMessage());
    }
    return changes.execute(
        status -> {
          repository.lockChanges();
          accounts.requireServerAdmin(actor);
          if (!entry(target).version().equals(change.expectedVersion()))
            throw new CallerFault(
                "Pricing changed; refresh and review the current rates before saving");
          repository.save(card, actor);
          return entry(target);
        });
  }

  private static BigDecimal decimal(String value) {
    if (value == null) return null;
    if (value.length() > 40 || !value.matches("[0-9]{1,18}(\\.[0-9]{1,12})?"))
      throw new IllegalArgumentException(
          "Amounts must be nonnegative decimal strings with at most 18 integer and 12 decimal digits");
    return new BigDecimal(value);
  }

  private static RateCard.Rates rates(Rates value) {
    return value == null
        ? null
        : new RateCard.Rates(
            decimal(value.input()),
            decimal(value.output()),
            decimal(value.cacheRead()),
            decimal(value.cacheWrite()));
  }

  private static String text(BigDecimal value) {
    return value == null ? null : value.toPlainString();
  }

  private static Rates view(RateCard.Rates value) {
    return value == null
        ? null
        : new Rates(
            text(value.input()),
            text(value.output()),
            text(value.cacheRead()),
            text(value.cacheWrite()));
  }

  private static Card view(RateCard card) {
    return new Card(
        card.revision(),
        card.mode(),
        card.currency(),
        view(card.rates()),
        card.tiers().stream().map(t -> new Tier(t.fromInputTokens(), view(t.rates()))).toList(),
        text(card.requestFee()),
        card.source(),
        card.validFrom(),
        card.validUntil());
  }
}
