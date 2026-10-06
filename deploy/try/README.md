# Local Docker try-out

The release bundle starts the public server and PostgreSQL with persistent named
volumes. It requires Docker with Compose 2.30+, at least 4 GiB of available memory, and an existing OpenAI-compatible
endpoint reachable from containers. One endpoint serves chat and embeddings;
there is no assumed second pool and no bundled model download.

Download `plowshare-try-<release>-linux-amd64.tar.gz` and its checksum from a
published release, verify the checksum with `sha256sum -c SHA256SUMS` (or `shasum -a 256 -c SHA256SUMS`), extract it and run:

```sh
./try.sh
```

The first run asks for model IDs, API key and an administrator account. Supplied
passwords are hidden and stored outside the bundle in owner-readable files.
Later starts reuse those settings. The web console is reachable only on the
local machine and requires the initial password to be changed after login.
Use `./try.sh stop` to stop it while retaining state; `./try.sh status` shows it.
`./try.sh configure` saves and validates settings without starting services.
Select another settings directory with `PLOWSHARE_TRY_STATE` before the first run.
Each directory selects its own Compose project and named volumes.

The first target is Linux amd64 (ARM hosts need Docker Desktop's amd64 emulation; native Linux ARM is not yet supported).
Native arm64 images and desktop installers require separate release validation.
The bundle uses the exact GHCR image digest recorded by its release. It does
not build from source or follow a moving `latest` tag. Configuration belongs to
the user; the bundle contains no installation or provider credentials.

## Embedding configuration

The minimal trial uses the product's legacy single-embedder compatibility mode,
which requires a **768-dimensional** encoder. Chat and non-embedding operations
work without a configured embedding tokenizer. Document processing and embedding
operations explicitly refuse until the model's matching tokenizer is configured;
the trial never substitutes a tokenizer or estimates embedding input length.

Copy the served model revision's `tokenizer.json` into
`<settings>/config/tokenizers/embedding.json`, calculate its SHA-256 and create
`<settings>/config/application.yml`:

```yaml
plowshare:
  llm:
    embedding-tokenizer:
      file: /etc/plowshare/tokenizers/embedding.json
      sha256: YOUR_VERIFIED_TOKENIZER_SHA256
    embedding-max-input-tokens: YOUR_MODEL_INPUT_LIMIT
```

Make tokenizer/config files readable by the server container, then run
`./try.sh` again. Supply the actual chat context length in that configuration
when the endpoint's capacity differs from the product defaults. For other
embedding dimensions, use the product's explicit dual-slot configuration;
changing the legacy dimension setting does not migrate its vector columns.
See [embedding configuration](https://github.com/myrddian/plowshare/blob/main/docs/embedding-evolution.md).

This local trial is separate from an operator's deployment. Public releases
contain the same product; installation pool layouts, personal workflows,
services, backups and deployment targets belong to the operator's overlay.

## What this preview verifies

The release runner checks the public product, starts the packaged image against
a new PostgreSQL volume, verifies authentication and a password change, and
restarts it to verify retained account state. It does not verify your model
provider or bundle a search service. Configure integrations separately through
the public manuals. This bundle must not be pointed at an existing production
database: it creates an independent trial with its own named volumes.
