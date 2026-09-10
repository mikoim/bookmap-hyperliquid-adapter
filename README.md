# Bookmap Hyperliquid Adapter

Read-only Bookmap Layer 0 market data for Hyperliquid perpetuals and spot pairs, including HIP-3
builder-deployed markets. Every live perpetual on every perp dex and every spot pair is listed and
available to subscribe to; the adapter publishes aggregate L2 order books and trades for the
instruments you subscribe to. It never requests credentials and never sends orders.

![Bookmap heatmap for the ETH/USDC perpetual delivered by the adapter](docs/eth.webp)

## Requirements

- Bookmap 7.8 or newer.
- No JDK. Bookmap supplies everything the adapter depends on at runtime.

## Install

1. Download `hyperliquid-adapter-1.4.0.jar` from the
   [Releases page](https://github.com/mikoim/bookmap-hyperliquid-adapter/releases).
2. Copy it into the `API/Layer0ApiModules` directory of your Bookmap installation. On Linux:

   ```text
   $HOME/.bookmap/API/Layer0ApiModules/hyperliquid-adapter-1.4.0.jar
   ```

3. Restart Bookmap.

To build the JAR yourself, see [docs/development.md](docs/development.md).

## Connect

Open Bookmap's Connectivity configuration dialog and select "Hyperliquid" (shown as "HYP" in the connection list).

![The Order book source dropdown in Bookmap's Connectivity configuration dialog](docs/connector.webp)

- **Order book source** selects where the book comes from: Hyperliquid (default), Borsa, or
  Hyperdash.
- **Use Hyperliquid testnet** applies to the Hyperliquid source only. Borsa and Hyperdash always
  serve Mainnet.

There are no credentials to enter.

Subscribe from Bookmap's Subscribe dialog. **Tick size** lists the ticks Hyperliquid actually quotes
at the instrument's current price magnitude — for HYPE near 88: 0.001, 0.002, 0.005, 0.01, 0.1, 1 —
and defaults to the finest of them. A coarser tick covers a wider price range with the same number
of levels, because the exchange aggregates before it sends.

HIP-3 markets keep their fully qualified `dex:coin` names, for example `xyz:CL`.

Spot pairs are listed as `BASE/QUOTE` under the `SPOT` type, for example `HYPE/USDC` or
`XAUT0/USDT0`. Names are the HyperCore token names, so the pair the Hyperliquid app shows as
`BTC/USDC` appears here as `UBTC/USDC`. On the wire the adapter subscribes by the exchange's own
pair id (`@107` for HYPE/USDC); that id never appears in Bookmap.

## Order book sources

| | Hyperliquid | Borsa | Hyperdash |
| --- | --- | --- | --- |
| Endpoint | `wss://api.hyperliquid.xyz/ws` | `wss://ws.borsa.cc/` | `wss://api.hyperdash.com/ws/orderbook` |
| Testnet | `wss://api.hyperliquid-testnet.xyz/ws` | Not available | Not available |
| Levels per side | 20 | 400 | 20 |
| Mark prices | Same connection | Extra Hyperliquid Mainnet connection | Extra Hyperliquid Mainnet connection |

Borsa and Hyperdash relay the Mainnet book. The adapter does not verify either relay against
Hyperliquid itself.

## Scope

Supported: `PERPETUAL` subscriptions for every live perpetual on every perp dex, HIP-3 markets
included, and `SPOT` subscriptions for every spot pair.

Not supported: historical data, account data, credentials, order entry, and gap filling. Order
APIs fail closed with a read-only system message.

## Data-health messages

Data-health transitions appear as Bookmap system messages and in the application log — WARN for
anomalies, INFO for recovery, subject to Bookmap's configured log level. Every message starts with
`data-status` and names the selected source, environment, symbol, and UTC time.

| Message | Meaning | What to do |
| --- | --- | --- |
| `BOOK_STALE` | No valid book has been accepted for this symbol for 30 seconds. Reception has gone quiet; this is not proof that the feed failed, and no reconnect is forced. | Wait. If it persists, reconnect or try another source. |
| `BOOK_RESYNCING` | A disconnect or an internal queue overflow invalidated the book. One incident invalidates every subscription at once and is reported once, naming all affected symbols. | Wait for `BOOK_RESUMED`. |
| `BOOK_RESUMED` | A replacement book has been published. This also clears a stale warning. | Nothing. |
| `TRADE_GAP_POSSIBLE` | A disconnect or a buffer overflow may have lost trades. The reported interval uses local processing times and is deliberately conservative; it is not an exact exchange-side range. | Treat trades in that interval as incomplete. They are not backfilled. |
| `TRADE_RESUMED` | A trade has been published again, ending the possible-gap interval. | Nothing. |
| `MARK_PRICE_UNAVAILABLE` | The mark-price connection failed or its subscription was rejected. Tick-size candidates keep their last known prices. | Wait. Reopening the socket alone does not clear this. |
| `MARK_PRICE_RESUMED` | Usable mark-price data has arrived again. | Nothing. |

The same ongoing condition is reported once; recovery re-arms the warning for a later incident.
Mark prices always come from Hyperliquid, whatever the selected order book source is.

## Limits

- Connection attempts, open connections, outbound frames, and subscription slots are budgeted per
  JVM and shared by every adapter provider in it; the budget allows ten concurrent connections.
  Another Bookmap process, or another application behind the same public IP, is invisible to that
  budget, so leave external headroom for it and for Hyperliquid's own limits.
- Borsa and Hyperdash each need a second connection to Hyperliquid Mainnet for mark prices, which
  caps a single JVM at five relay-backed providers.
- During a disconnect the adapter reconnects and resynchronizes, but there is no historical gap fill
  and trades can be missed.
- On Testnet the instrument list holds roughly 630 perpetuals across about 200 perp dexes and
  roughly 1,300 spot pairs, most of them throwaway markets deployed by other developers.
- An instrument whose mark price has not arrived yet lists its full-precision grid as the default
  tick. For a high-priced spot pair that grid can exceed Bookmap's integer price range, and the
  subscription fails with an unsupported-price message until a mark price arrives; resubscribe
  then.

## For developers

Build instructions, quality gates, and the code layout are in
[docs/development.md](docs/development.md).
