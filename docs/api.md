# Bots4Velo API layers

Bots4Velo exposes two additive integration layers. Existing integrations can keep using
`dev.nulli0n.vbot.api.Bots4VeloApi` unchanged.

## CommonBotApi

`dev.nulli0n.vbot.api.common.CommonBotApi` is the convenience API for everyday controls:

- list or select bots with `all`, `@group:<name>`, `@tag:<name>`, `@server:<name>`, or an ID;
- batch start, stop, reconnect, command, movement, look, jump, swing, and sneak actions;
- switch a group of authenticated bots to a Velocity backend;
- start/pause configured behavior and follow a player;
- observe normalized bot events.

It is available after proxy initialization:

```java
import dev.nulli0n.vbot.api.common.CommonBotApiProvider;

CommonBotApiProvider.get().ifPresent(api -> {
    api.start("@group:farm");
    api.command("@tag:lobby", "spawn");
    api.switchServer("all", "survival").thenAccept(results ->
        results.forEach(result -> logger.info(result.status().name())));
});
```

`BatchResult` reports the number of matched and accepted bots. A bot action returning
`false` means the bot was not ready for that action; it does not throw into the caller.
`ServerSwitchResult` reports authentication-pending, backend-not-found, and failure states
individually.

## Compatibility promise

The original `Bots4VeloApi` and the merged addon SPI are not modified by this convenience
layer. New controls are exposed through `CommonBotApiProvider`, so consumers can opt in
without recompiling existing integrations. The provider is cleared during proxy shutdown.
