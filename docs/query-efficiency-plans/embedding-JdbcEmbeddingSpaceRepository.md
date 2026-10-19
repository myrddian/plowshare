# JdbcEmbeddingSpaceRepository

Status: Proposed; no implementation. Baseline: `fc36351f239b62984363ababaa1de1139402aafb`.

[Source](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/embedding/JdbcEmbeddingSpaceRepository.java#L20) implements the immutable embedding descriptor registry. `register` inserts with `ON CONFLICT DO NOTHING RETURNING`, then selects by the exact descriptor only after conflict; `find` selects by ID. `EmbeddingConfig` and `JdbcEmbeddingWorkRepository` resolve these descriptors. The fresh statement after a registration conflict is intentional READ COMMITTED visibility handling, not fetch-discard-refetch.

[V115](../../plowshare-server/src/main/resources/db/migration/V115__code_and_prose_embeddings.sql#L9) defines descriptor identity, uniqueness and immutability. Preserve these constraints. Narrow explicit columns improve mapping stability; no extra index or stored procedure is justified for primary-key/descriptor lookups. Remove repeated `spaces.find` calls when assembling several profiles by retaining the already-resolved immutable space.

This is a strong first cache candidate: database identity plus space ID and projection, with the content-derived descriptor identity as version. Cache the validated immutable DTO through the proposed bounded expected O(1) LRU; coalesce misses and do not admit missing IDs without insertion invalidation. Publish successful registration only after its owning transaction commits. Cover external registration/deletion or maintenance with durable invalidation; immutable definitions still need namespace reset after reconnect ambiguity. Descriptor reuse never caches permission admission for protected source content.

Mock tests should show one descriptor load shared across active/target profile assembly and concurrent misses. Focused future PostgreSQL tests cover exact concurrent registration conflict visibility, hash identity and immutable triggers. No database run is required for this plan.
