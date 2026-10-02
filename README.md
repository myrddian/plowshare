# Plowshare

Plowshare provides infrastructure for remote agent work. The server owns agent
execution and durable state; clients connect through MCP, the terminal, the
desktop application, or the browser console. Models and providers are configured
by the operator.

Start with the [user manual](docs/README.md), [installation guide](docs/distributions.md)
and [Docker deployment guide](deploy/docker/README.md).

## Build from source

Use Java 21, Node.js 22.12 or newer, pnpm 10.34.5, and Python 3.
Docker is needed for integration tests using Testcontainers.

```sh
./gradlew assemble
./gradlew check
```

The repository includes application code, build tools, automated software tests
and user documentation. Executable protocol fixtures live in `test-support/`.

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
Third-party components retain their own licenses and notices.
