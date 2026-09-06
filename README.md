# Bookmap Hyperliquid Adapter

Read-only Bookmap Layer 0 market data for Hyperliquid default-DEX perpetuals. The adapter
loads perpetual metadata over REST, then publishes aggregate L2 books and trades over one
WebSocket. The `Order book source` dropdown selects Hyperliquid (default), Borsa, or Hyperdash;
the `Use Hyperliquid testnet` checkbox only applies to Hyperliquid. It never requests
credentials and never sends orders.

Runtime verification: Not verified in Bookmap runtime.

## Scope and behavior

The adapter supports the exact read-only perpetual scope exposed by Hyperliquid's default DEX:

- With the Hyperliquid source, the Mainnet/Testnet checkbox selects both the metadata REST
  endpoint and the market-data WebSocket. Borsa (`wss://ws.borsa.cc/`) and Hyperdash
  (`wss://api.hyperdash.com/ws/orderbook`) relay the Mainnet book, so they always load
  metadata from Hyperliquid Mainnet and ignore the checkbox.
- Borsa sends one full seed per connection followed by per-level deltas; the adapter folds the
  deltas locally and replaces the published book on every reconnect. Hyperdash sends full
  snapshots like Hyperliquid and requires browser-style handshake headers, which the adapter
  adds automatically. The adapter does not verify either relay against Hyperliquid itself.
- Only `PERPETUAL` subscriptions are accepted; Spot, history, account data, credentials, orders,
  and gap filling are not implemented.
- Each subscription uses one `l2Book` feed and one `trades` feed on a single WebSocket. Snapshots
  are aggregate L2 data, with at most 20 levels per side. Trades received while disconnected can
  be missed.
- `pips = 10^-(6-szDecimals)` is a lossless Bookmap display quantum for this market-data-only
  adapter, not an asserted Hyperliquid order tick.
- Metadata is validated as a complete universe before delisted instruments are filtered.

The adapter has no trading, order, account, credential, or private-user-data behavior. Order APIs
fail closed with a read-only system message.

## Build and install

Prerequisites are JDK 21 through JDK 25 and a shell capable of running the Gradle wrapper. JDK 20
or earlier and JDK 26 or later are rejected by the build; those environments require a prior
Gradle and quality-tool upgrade.

Use the system JDK normally. The repository's `references/jdk-25.0.4.1` directory is an explicit
`JAVA_HOME` or `-Dorg.gradle.java.home` fallback only when a system-JDK issue is being isolated;
it is not a general runtime selection mechanism.

```bash
./gradlew clean build
```

The adapter compiles to Java 8 bytecode. The thin adapter JAR is
`build/libs/hyperliquid-adapter-1.0.0.jar`; Bookmap supplies the API, Gson, and Jetty dependencies.
Load the module through its Bookmap annotations, or place it in the Bookmap `Layer0ApiModules`
directory according to the host installation's module-loading configuration.

## Operating limits

Connection attempts, open connections, outbound frames, and subscription slots are shared across
all adapter providers in the same JVM. Another Bookmap JVM/process, or another application using
the same public IP, is invisible to this in-process budget; operators must retain external
headroom for those consumers and for Hyperliquid's external limits.

The adapter uses per-side 20-level aggregate snapshots. During a disconnect, reconnect and full
resynchronization are attempted, but no historical gap fill is available and trades may be missed.
Borsa deltas are not self-healing after a dropped frame; the adapter reconnects and resynchronizes
from a fresh seed instead.

## Quality checks

Run the independent quality gates before distributing the JAR:

```bash
./gradlew --no-daemon spotlessCheck
./gradlew --no-daemon checkstyleMain checkstyleTest
./gradlew --no-daemon spotbugsMain
./gradlew --no-daemon test
./gradlew --no-daemon check
./gradlew --no-daemon clean build
```

SpotBugs HTML and XML reports are written under `build/reports/spotbugs`. The build's
`verifyJava8Bytecode` task checks that compiled classes use Java 8 major version 52.

## Runtime verification

Runtime verification: Not verified in Bookmap runtime.

