# Project structure

The repository is organized by integration boundary. Existing package locations are kept
stable to avoid breaking plugin imports.

```text
src/main/java/dev/nulli0n/vbot/
  api/                 legacy API and additive common API providers
    common/            batch-oriented CommonBotApi
  addon/               isolated addon loader and core addon bridge
  backend/             Velocity-to-Paper control and capability cache
  bot/                 sessions, lifecycle, behavior, reconnect, and events
  command/             /vbot command UI and handlers
  config/              YAML, managed bots, credentials, and reload validation
  observe/             Prometheus and webhook output
  protocol/            detection and adapter selection
  tab/                 optional TAB integration

addon-api/              stable addon-facing SPI artifact
backend-protocol/       shared signed backend-control protocol
transport-api/          version-neutral bot transport contract
adapters/                protocol-specific transport implementations
paper-companion/        optional Paper-side authoritative controls
docs/                   operator, API, and architecture documentation
```

The `api/common` package is deliberately additive; it does not move or rename the existing
`dev.nulli0n.vbot.api.Bots4VeloApi` classes.
