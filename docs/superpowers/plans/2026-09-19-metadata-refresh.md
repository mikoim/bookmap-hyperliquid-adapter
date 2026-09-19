# Metadata Refresh Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refresh the instrument list every 30 minutes and after every successful reconnect, so listings and delistings reach Bookmap without a new login.

**Architecture:** One metadata fetch becomes the single-use `MetadataRequest`, shared by login (`HyperliquidConnector`) and by the new `MetadataRefresher`, which owns the 30-minute timer and the reconnect trigger. `HyperliquidSession` applies a refreshed list, republishes it through the existing `sink.onKnownInstruments` path, and reports a subscribed instrument that left the list once, without closing its subscription.

**Tech Stack:** Java 8 source level (no `var`, no `List.of`, no lambdas are forbidden but the code base prefers anonymous classes in `main`), JUnit 4, Gson 2.4, Jetty 9.3 (untouched here), Gradle wrapper.

**Spec:** `docs/superpowers/specs/2026-09-19-metadata-refresh-design.md`

## Global Constraints

- Work directly on `main`; commit after every task. End every commit message with
  `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.
- Gradle only starts with the JDK passed explicitly, and dependencies are cached offline. The shell
  is fish, so use `env`:
  `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline <tasks>`
- Full gate before every commit: `... ./gradlew --offline spotlessApply check verifyJava8Bytecode`
  (Spotless, Checkstyle with zero warnings, SpotBugs at LOW confidence, Java 8 bytecode).
- Checkstyle requires Javadoc on every public and package-private type and on public methods.
- All mutable state is touched only on the state lane (`dispatcher::submitControl` in production).
- `HyperliquidConnectorTest` must pass **unmodified** after Task 3.
- Constants, verbatim from the spec: refresh interval `1_800_000` ms, minimum gap between refresh
  starts `60_000` ms, per-request timeout `10_000` ms.
- System message text, verbatim:
  `<alias>[, <alias>...] no longer listed by Hyperliquid; open subscriptions stay active until removed`
- Diagnostic text, verbatim: `metadata refresh failed: <failure message>`
- Large-file rule: `HyperliquidSession.java` and `HyperliquidConnector.java` exceed 1,000 lines.
  Edit them with targeted `Edit` calls only; never rewrite them whole. After each edit run
  `git diff --check` and read `git diff`.

## File Structure

| File | Action | Responsibility |
| --- | --- | --- |
| `src/main/java/.../transport/TransportFailure.java` | Modify | Gains static `classify` |
| `src/main/java/.../MetadataRequest.java` | Create | One metadata fetch: two POSTs, join, parse, one callback |
| `src/main/java/.../HyperliquidConnector.java` | Modify | Login uses `MetadataRequest`; metadata plumbing removed |
| `src/main/java/.../session/MetadataRequestFactory.java` | Create | Public seam: `SourceProfile` + callback → `MetadataRequest` |
| `src/main/java/.../session/MetadataRefresher.java` | Create | 30-minute timer, reconnect trigger, single flight |
| `src/main/java/.../session/HyperliquidSession.java` | Modify | Starts/stops the refresher, applies results, unlisted notice |
| `src/main/java/.../Provider.java` | Modify | Production wiring of `MetadataRequestFactory` |
| `src/test/java/.../transport/TransportFailureTest.java` | Create | `classify` |
| `src/test/java/.../MetadataRequestTest.java` | Create | `MetadataRequest` contract |
| `src/test/java/.../session/MetadataRefresherTest.java` | Create | Timer and trigger contract |
| `src/test/java/.../session/HyperliquidSessionMetadataRefreshTest.java` | Create | Session behavior (new file: `HyperliquidSessionLifecycleTest` is already 1,113 lines) |
| 7 existing test files constructing `HyperliquidSession` | Modify | Pass the new constructor argument |
| `src/test/java/.../ProviderEndToEndTest.java` | Modify | One end-to-end case |
| `README.md`, `docs/development.md` | Modify | User-visible behavior, package table |

`...` is `com/bookmap/plugins/layer0/hyperliquid` throughout.

The spec places the session tests in `HyperliquidSessionLifecycleTest`; this plan puts them in a new
file with its own small fixture because that file is already too large to work in reliably. The
cases are the spec's, unchanged.

---

### Task 1: `TransportFailure.classify`

**Files:**
- Modify: `src/main/java/com/bookmap/plugins/layer0/hyperliquid/transport/TransportFailure.java`
- Modify: `src/main/java/com/bookmap/plugins/layer0/hyperliquid/HyperliquidConnector.java` (`classify`, `isNetworkFailure`, their call sites, imports)
- Test: `src/test/java/com/bookmap/plugins/layer0/hyperliquid/transport/TransportFailureTest.java`

**Interfaces:**
- Produces: `public static TransportFailure TransportFailure.classify(Throwable failure, TransportFailure.Kind fallback)`

- [ ] **Step 1: Write the failing test**

Create `TransportFailureTest.java`:

```java
package com.bookmap.plugins.layer0.hyperliquid.transport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.net.UnknownHostException;
import java.util.concurrent.ExecutionException;
import org.junit.Test;

/** Pins how raw failures are sorted into the kinds that drive login and reconnect policy. */
public class TransportFailureTest {

  @Test
  public void networkCauseAnywhereInTheChainWinsOverTheFallback() {
    Throwable failure =
        new ExecutionException(new IllegalStateException(new UnknownHostException("no dns")));

    TransportFailure classified =
        TransportFailure.classify(failure, TransportFailure.Kind.PROTOCOL);

    assertEquals(TransportFailure.Kind.NETWORK, classified.kind());
    assertSame(failure, classified.cause());
    assertEquals(failure.getMessage(), classified.message());
  }

  @Test
  public void otherFailuresKeepTheFallbackKind() {
    TransportFailure classified =
        TransportFailure.classify(
            new IllegalArgumentException("bad json"), TransportFailure.Kind.PROTOCOL);

    assertEquals(TransportFailure.Kind.PROTOCOL, classified.kind());
    assertEquals("bad json", classified.message());
  }

  @Test
  public void missingFailureKeepsTheFallbackKindWithoutAMessage() {
    TransportFailure classified = TransportFailure.classify(null, TransportFailure.Kind.NETWORK);

    assertEquals(TransportFailure.Kind.NETWORK, classified.kind());
    assertNull(classified.message());
    assertNull(classified.cause());
  }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test --tests '*TransportFailureTest'`
Expected: compilation failure, `cannot find symbol ... classify`. A compile error is the only
possible red here because the method does not exist yet.

- [ ] **Step 3: Add `classify` to `TransportFailure`**

Add these imports and members (members go after the constructor):

```java
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;
```

```java
  /**
   * Classifies a raw failure. A network exception anywhere in the cause chain makes it {@link
   * Kind#NETWORK}; anything else keeps the caller's fallback kind.
   */
  public static TransportFailure classify(Throwable failure, Kind fallback) {
    Kind kind = isNetworkFailure(failure) ? Kind.NETWORK : fallback;
    String message = failure == null ? null : failure.getMessage();
    return new TransportFailure(kind, message, failure);
  }

  private static boolean isNetworkFailure(Throwable failure) {
    Throwable current = failure;
    while (current != null) {
      if (current instanceof UnknownHostException
          || current instanceof NoRouteToHostException
          || current instanceof SocketException
          || current instanceof ConnectException
          || current instanceof SocketTimeoutException
          || current instanceof TimeoutException) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }
```

- [ ] **Step 4: Point the connector at it**

In `HyperliquidConnector.java`:
- delete the private methods `classify(Throwable, TransportFailure.Kind)` and
  `isNetworkFailure(Throwable)`;
- replace every remaining `classify(` call with `TransportFailure.classify(` (first confirm the
  count: `grep -c "classify(" HyperliquidConnector.java` is 8 before the deletion — 7 call sites
  plus the declaration; after the edit `grep -n "[^.]classify(" ...` must print nothing);
- delete the imports that become unused: `java.net.ConnectException`,
  `java.net.NoRouteToHostException`, `java.net.SocketException`,
  `java.net.SocketTimeoutException`, `java.net.UnknownHostException`. Keep
  `java.util.concurrent.TimeoutException`; the handshake timeout still constructs one.

- [ ] **Step 5: Run the gate**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline spotlessApply check verifyJava8Bytecode`
Expected: `BUILD SUCCESSFUL`; `TransportFailureTest` has 3 passing tests and
`HyperliquidConnectorTest` is unchanged and green.

- [ ] **Step 6: Commit**

```bash
git add -A src
git commit -m "refactor: classify transport failures in TransportFailure itself"
```

---

### Task 2: `MetadataRequest`

**Files:**
- Create: `src/main/java/com/bookmap/plugins/layer0/hyperliquid/MetadataRequest.java`
- Test: `src/test/java/com/bookmap/plugins/layer0/hyperliquid/MetadataRequestTest.java`

**Interfaces:**
- Consumes: `TransportFailure.classify(Throwable, Kind)` (Task 1);
  `HyperliquidTransport.postJson(URI, String, String, long, HttpCallback)`;
  `HyperliquidMetaParser.parseAllPerpMetas(String)`, `parseSpotMeta(String)`,
  `static combine(List<Instrument>, List<Instrument>)`, all throwing `ProtocolException`.
- Produces:

```java
public final class MetadataRequest {
  public interface Callback {
    void onInstruments(List<Instrument> instruments);
    void onFailure(TransportFailure failure);
  }
  public MetadataRequest(HyperliquidTransport transport, HyperliquidMetaParser metaParser,
      Consumer<Runnable> stateSubmitter, URI infoUri, Callback callback);
  public void start();   // state lane; IllegalStateException on a second call
  public void cancel();  // state lane; no callback afterwards
}
```

- [ ] **Step 1: Write the failing tests**

Create `MetadataRequestTest.java`. The state lane is a queue so the tests can prove that transport
callbacks are re-submitted rather than handled on the transport thread.

```java
package com.bookmap.plugins.layer0.hyperliquid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.bookmap.plugins.layer0.hyperliquid.model.Instrument;
import com.bookmap.plugins.layer0.hyperliquid.parse.HyperliquidMetaParser;
import com.bookmap.plugins.layer0.hyperliquid.transport.HyperliquidTransport;
import com.bookmap.plugins.layer0.hyperliquid.transport.TransportFailure;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.Test;

/** Pins the single-use metadata fetch shared by login and the periodic refresh. */
public class MetadataRequestTest {

  private static final URI INFO_URI = URI.create("https://api.hyperliquid.xyz/info");

  @Test
  public void bothResponsesYieldOneCombinedList() {
    Fixture fixture = new Fixture();
    fixture.request.start();

    assertEquals(INFO_URI, fixture.transport.httpUri());
    assertEquals(10_000L, fixture.transport.httpTimeoutMillis());
    fixture.transport.completeSpotMeta(200, TestMetadata.spotMetaWithUsdcPairs("HYPE"));
    fixture.transport.completeMetaOnly(200, perps("BTC"));
    fixture.drain();

    assertEquals("[BTC, HYPE/USDC]", fixture.symbols.toString());
    assertTrue(fixture.failures.isEmpty());
    assertEquals(1, fixture.instrumentCallbacks);
  }

  @Test
  public void responsesMayArriveInEitherOrder() {
    Fixture fixture = new Fixture();
    fixture.request.start();

    fixture.transport.completeMetaOnly(200, perps("BTC"));
    fixture.drain();
    assertEquals(0, fixture.instrumentCallbacks);
    fixture.transport.completeSpotMeta(200, TestMetadata.emptySpotMeta());
    fixture.drain();

    assertEquals("[BTC]", fixture.symbols.toString());
  }

  @Test
  public void transportCallbacksWaitForTheStateLane() {
    Fixture fixture = new Fixture();
    fixture.request.start();

    fixture.transport.completeMeta(200, perps("BTC"));

    assertEquals(0, fixture.instrumentCallbacks);
    fixture.drain();
    assertEquals(1, fixture.instrumentCallbacks);
  }

  @Test
  public void perpFailureCancelsSpotAndReportsOnce() {
    Fixture fixture = new Fixture();
    fixture.request.start();

    fixture.transport.failMeta(new UnknownHostException("no dns"));
    fixture.drain();

    assertEquals(1, fixture.failures.size());
    assertEquals(TransportFailure.Kind.NETWORK, fixture.failures.get(0).kind());
    assertEquals(0, fixture.transport.pendingHttpCount());
    assertTrue(fixture.transport.httpCancelled());
  }

  @Test
  public void spotFailureCancelsPerpAndReportsOnce() {
    Fixture fixture = new Fixture();
    fixture.request.start();

    fixture.transport.failSpotMeta(new UnknownHostException("no dns"));
    fixture.drain();

    assertEquals(1, fixture.failures.size());
    assertEquals(0, fixture.transport.pendingHttpCount());
  }

  @Test
  public void nonSuccessStatusIsARemoteFailure() {
    Fixture fixture = new Fixture();
    fixture.request.start();

    fixture.transport.completeMetaOnly(503, "unavailable");
    fixture.drain();

    assertEquals(TransportFailure.Kind.REMOTE, fixture.failures.get(0).kind());
    assertEquals("metadata request returned HTTP 503", fixture.failures.get(0).message());
  }

  @Test
  public void unparsableBodyIsAProtocolFailure() {
    Fixture fixture = new Fixture();
    fixture.request.start();

    fixture.transport.completeMetaOnly(200, "{}");
    fixture.drain();

    assertEquals(TransportFailure.Kind.PROTOCOL, fixture.failures.get(0).kind());
  }

  @Test
  public void aCoinListedTwiceAcrossMarketsIsAProtocolFailure() {
    Fixture fixture = new Fixture();
    fixture.request.start();

    // The spot pair's wire coin is "@1"; a perp with the same name collides in combine().
    fixture.transport.completeSpotMeta(200, TestMetadata.spotMetaWithUsdcPairs("HYPE"));
    fixture.transport.completeMetaOnly(200, perps("@1"));
    fixture.drain();

    assertEquals(1, fixture.failures.size());
    assertEquals(TransportFailure.Kind.PROTOCOL, fixture.failures.get(0).kind());
    assertEquals(0, fixture.instrumentCallbacks);
  }

  @Test
  public void cancelSilencesLateResponses() {
    Fixture fixture = new Fixture();
    fixture.request.start();

    fixture.request.cancel();
    fixture.transport.lateCompleteMeta(200, perps("BTC"));
    fixture.drain();

    assertEquals(0, fixture.instrumentCallbacks);
    assertTrue(fixture.failures.isEmpty());
    assertEquals(0, fixture.transport.pendingHttpCount());
  }

  @Test
  public void responsesAfterCompletionAreIgnored() {
    Fixture fixture = new Fixture();
    fixture.request.start();
    fixture.transport.completeMeta(200, perps("BTC"));
    fixture.drain();

    fixture.transport.lateCompleteMeta(503, "late");
    fixture.drain();

    assertEquals(1, fixture.instrumentCallbacks);
    assertTrue(fixture.failures.isEmpty());
  }

  @Test
  public void aThrowingTransportFailsSynchronouslyAndOnce() {
    final List<TransportFailure> failures = new ArrayList<TransportFailure>();
    MetadataRequest request =
        new MetadataRequest(
            new ThrowingTransport(),
            new HyperliquidMetaParser(),
            new Consumer<Runnable>() {
              @Override
              public void accept(Runnable task) {
                task.run();
              }
            },
            INFO_URI,
            new MetadataRequest.Callback() {
              @Override
              public void onInstruments(List<Instrument> instruments) {
                fail("no instruments expected");
              }

              @Override
              public void onFailure(TransportFailure failure) {
                failures.add(failure);
              }
            });

    request.start();

    assertEquals(1, failures.size());
    assertEquals(TransportFailure.Kind.NETWORK, failures.get(0).kind());
  }

  @Test(expected = IllegalStateException.class)
  public void startingTwiceIsRejected() {
    Fixture fixture = new Fixture();
    fixture.request.start();
    fixture.request.start();
  }

  private static String perps(String... symbols) {
    return TestMetadata.allPerpMetas(TestMetadata.universe(symbols));
  }

  private static final class Fixture {
    private final FakeHyperliquidTransport transport = new FakeHyperliquidTransport();
    private final ArrayDeque<Runnable> lane = new ArrayDeque<Runnable>();
    private final List<String> symbols = new ArrayList<String>();
    private final List<TransportFailure> failures = new ArrayList<TransportFailure>();
    private int instrumentCallbacks;
    private final MetadataRequest request =
        new MetadataRequest(
            transport,
            new HyperliquidMetaParser(),
            new Consumer<Runnable>() {
              @Override
              public void accept(Runnable task) {
                lane.addLast(task);
              }
            },
            INFO_URI,
            new MetadataRequest.Callback() {
              @Override
              public void onInstruments(List<Instrument> instruments) {
                instrumentCallbacks++;
                for (Instrument instrument : instruments) {
                  symbols.add(instrument.symbol());
                }
              }

              @Override
              public void onFailure(TransportFailure failure) {
                failures.add(failure);
              }
            });

    private void drain() {
      while (!lane.isEmpty()) {
        lane.removeFirst().run();
      }
    }
  }

  /** A transport whose HTTP client is not running: every post throws. */
  private static final class ThrowingTransport implements HyperliquidTransport {
    @Override
    public void start() {
      // nothing to start
    }

    @Override
    public Cancellable postJson(
        URI uri, String contentType, String body, long timeoutMillis, HttpCallback callback) {
      throw new IllegalStateException("client stopped", new UnknownHostException("no dns"));
    }

    @Override
    public Cancellable connect(
        URI uri, Map<String, String> headers, long timeoutMillis, SocketCallback callback) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void close() {
      // nothing to close
    }
  }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test --tests '*MetadataRequestTest'`
Expected: compilation failure, `cannot find symbol class MetadataRequest`.

- [ ] **Step 3: Write `MetadataRequest`**

```java
package com.bookmap.plugins.layer0.hyperliquid;

import com.bookmap.plugins.layer0.hyperliquid.model.Instrument;
import com.bookmap.plugins.layer0.hyperliquid.parse.HyperliquidMetaParser;
import com.bookmap.plugins.layer0.hyperliquid.parse.ProtocolException;
import com.bookmap.plugins.layer0.hyperliquid.transport.HyperliquidTransport;
import com.bookmap.plugins.layer0.hyperliquid.transport.TransportFailure;
import java.net.URI;
import java.util.List;
import java.util.function.Consumer;

/**
 * One fetch of the instrument universe: {@code allPerpMetas} and {@code spotMeta} in parallel,
 * joined into a single validated list. An instance is used once. Every method, and every callback,
 * runs on the supplied state lane.
 */
public final class MetadataRequest {

  static final String PERP_METADATA_REQUEST_JSON = "{\"type\":\"allPerpMetas\"}";
  static final String SPOT_METADATA_REQUEST_JSON = "{\"type\":\"spotMeta\"}";
  private static final long TIMEOUT_MILLIS = 10_000L;

  /** Receives the outcome exactly once, unless the request is cancelled first. */
  public interface Callback {

    /** Reports the combined perpetual and spot instruments. */
    void onInstruments(List<Instrument> instruments);

    /** Reports the first failure of either request. */
    void onFailure(TransportFailure failure);
  }

  private final HyperliquidTransport transport;
  private final HyperliquidMetaParser metaParser;
  private final Consumer<Runnable> stateSubmitter;
  private final URI infoUri;
  private final Callback callback;

  private HyperliquidTransport.Cancellable perpRequest;
  private HyperliquidTransport.Cancellable spotRequest;
  private List<Instrument> perpInstruments;
  private List<Instrument> spotInstruments;
  private boolean started;
  private boolean finished;

  /** Creates a request against one info endpoint. */
  public MetadataRequest(
      HyperliquidTransport transport,
      HyperliquidMetaParser metaParser,
      Consumer<Runnable> stateSubmitter,
      URI infoUri,
      Callback callback) {
    if (transport == null
        || metaParser == null
        || stateSubmitter == null
        || infoUri == null
        || callback == null) {
      throw new IllegalArgumentException("metadata request dependencies must not be null");
    }
    this.transport = transport;
    this.metaParser = metaParser;
    this.stateSubmitter = stateSubmitter;
    this.infoUri = infoUri;
    this.callback = callback;
  }

  /**
   * Posts both requests. A transport that throws fails the request synchronously, so a caller must
   * record that the request is in flight before calling this.
   */
  public void start() {
    if (started) {
      throw new IllegalStateException("metadata request already started");
    }
    started = true;
    try {
      perpRequest = post(PERP_METADATA_REQUEST_JSON, true);
      spotRequest = post(SPOT_METADATA_REQUEST_JSON, false);
    } catch (RuntimeException failure) {
      fail(TransportFailure.classify(failure, TransportFailure.Kind.NETWORK));
    }
  }

  /** Aborts both requests; no callback is delivered afterwards. */
  public void cancel() {
    finished = true;
    cancelRequests();
  }

  private HyperliquidTransport.Cancellable post(String requestJson, final boolean perp) {
    return transport.postJson(
        infoUri,
        "application/json",
        requestJson,
        TIMEOUT_MILLIS,
        new HyperliquidTransport.HttpCallback() {
          @Override
          public void onComplete(final int statusCode, final String body, final Throwable failure) {
            stateSubmitter.accept(
                new Runnable() {
                  @Override
                  public void run() {
                    complete(perp, statusCode, body, failure);
                  }
                });
          }
        });
  }

  /** The first failure of either side ends the request; a late or repeated response is ignored. */
  private void complete(boolean perp, int statusCode, String body, Throwable failure) {
    if (finished || (perp ? perpInstruments : spotInstruments) != null) {
      return;
    }
    if (failure != null) {
      fail(TransportFailure.classify(failure, TransportFailure.Kind.NETWORK));
      return;
    }
    if (statusCode < 200 || statusCode >= 300) {
      fail(
          new TransportFailure(
              TransportFailure.Kind.REMOTE, "metadata request returned HTTP " + statusCode, null));
      return;
    }
    List<Instrument> combined;
    try {
      if (perp) {
        perpInstruments = metaParser.parseAllPerpMetas(body);
      } else {
        spotInstruments = metaParser.parseSpotMeta(body);
      }
      if (perpInstruments == null || spotInstruments == null) {
        return;
      }
      combined = HyperliquidMetaParser.combine(perpInstruments, spotInstruments);
    } catch (ProtocolException invalid) {
      fail(TransportFailure.classify(invalid, TransportFailure.Kind.PROTOCOL));
      return;
    }
    finished = true;
    callback.onInstruments(combined);
  }

  private void fail(TransportFailure failure) {
    if (finished) {
      return;
    }
    finished = true;
    cancelRequests();
    callback.onFailure(failure);
  }

  private void cancelRequests() {
    if (perpRequest != null) {
      perpRequest.cancel();
      perpRequest = null;
    }
    if (spotRequest != null) {
      spotRequest.cancel();
      spotRequest = null;
    }
  }
}
```

Note for the implementer: `FakeHyperliquidTransport.pendingHttpCount()` counts handles that are
neither completed nor cancelled, which is why `cancelRequests()` cancelling an already-completed
handle is harmless in the tests and in Jetty (`Request.abort` on a finished request is a no-op).

- [ ] **Step 4: Run the tests**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test --tests '*MetadataRequestTest'`
Expected: 12 tests pass.

- [ ] **Step 5: Run the gate and commit**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline spotlessApply check verifyJava8Bytecode`
Expected: `BUILD SUCCESSFUL`.

```bash
git add -A src
git commit -m "feat: add a single-use metadata request for perps and spot"
```

---

### Task 3: Login goes through `MetadataRequest`

**Files:**
- Modify: `src/main/java/com/bookmap/plugins/layer0/hyperliquid/HyperliquidConnector.java`
- Test: `src/test/java/com/bookmap/plugins/layer0/hyperliquid/HyperliquidConnectorTest.java` (**unmodified**; it is the regression suite)

**Interfaces:**
- Consumes: `MetadataRequest` (Task 2).
- Produces: no API change. `HyperliquidConnector`'s constructors, public methods and `Listener` stay
  exactly as they are.

This is a behavior-preserving move, so there is no new failing test: the existing metadata cases in
`HyperliquidConnectorTest` (both requests posted in perp-then-spot order, either failure fails
login and cancels the other, late callbacks ignored, close cancels) are the specification.

- [ ] **Step 1: Confirm the suite is green before touching anything**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test --tests '*HyperliquidConnectorTest' --tests '*ProviderEndToEndTest'`
Expected: all pass.

- [ ] **Step 2: Replace the fields**

Replace

```java
  private HyperliquidTransport.Cancellable perpMetadataRequest;
  private HyperliquidTransport.Cancellable spotMetadataRequest;
  private List<Instrument> perpInstruments;
  private List<Instrument> spotInstruments;
```

with

```java
  private MetadataRequest metadataRequest;
```

and delete the constants `METADATA_TIMEOUT_MILLIS`, `PERP_METADATA_REQUEST_JSON` and
`SPOT_METADATA_REQUEST_JSON`.

- [ ] **Step 3: Replace the login fetch**

Replace the `try` block in `startOnStateLane`:

```java
    try {
      transport.start();
    } catch (Exception failure) {
      reportInitialFailure(TransportFailure.classify(failure, TransportFailure.Kind.NETWORK));
      return;
    }
    // The field is set before start(): a transport that throws reports its failure synchronously.
    metadataRequest =
        new MetadataRequest(
            transport,
            metaParser,
            stateSubmitter,
            profile.infoUri(),
            new MetadataRequest.Callback() {
              @Override
              public void onInstruments(List<Instrument> instruments) {
                metadataRequest = null;
                if (closed) {
                  return;
                }
                listener.onMetadata(instruments);
                attemptConnection(true);
              }

              @Override
              public void onFailure(TransportFailure failure) {
                metadataRequest = null;
                if (!closed) {
                  reportInitialFailure(failure);
                }
              }
            });
    metadataRequest.start();
```

Delete `postMetadata`, `completeMetadata`, `failMetadata` and `cancelMetadataRequests`.

- [ ] **Step 4: Cancel on close**

In `closeOnStateLane`, replace `cancelMetadataRequests();` with:

```java
    if (metadataRequest != null) {
      metadataRequest.cancel();
      metadataRequest = null;
    }
```

Remove imports that are now unused (`ProtocolException`; keep `HyperliquidMetaParser`, it is still
a constructor parameter type). Let the compiler and Checkstyle's `UnusedImports` be the judge.

- [ ] **Step 5: Run the gate**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline spotlessApply check verifyJava8Bytecode`
Expected: `BUILD SUCCESSFUL`, and `git status --short` lists **only**
`HyperliquidConnector.java`. If `HyperliquidConnectorTest.java` needed an edit, stop: the move
changed behavior, which the spec forbids.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/bookmap/plugins/layer0/hyperliquid/HyperliquidConnector.java
git commit -m "refactor: fetch login metadata through MetadataRequest"
```

---

### Task 4: `MetadataRefresher`

**Files:**
- Create: `src/main/java/com/bookmap/plugins/layer0/hyperliquid/session/MetadataRefresher.java`
- Test: `src/test/java/com/bookmap/plugins/layer0/hyperliquid/session/MetadataRefresherTest.java`

**Interfaces:**
- Consumes: `MetadataRequest`, `MetadataRequest.Callback` (Task 2); `CancellableScheduler.schedule(Runnable, long)`.
- Produces (package-private, package `...hyperliquid.session`):

```java
final class MetadataRefresher {
  static final long REFRESH_INTERVAL_MILLIS = 1_800_000L;
  static final long MIN_REFRESH_GAP_MILLIS = 60_000L;
  interface Listener {
    void onMetadataRefreshed(List<Instrument> instruments);
    void onMetadataRefreshFailed(TransportFailure failure);
  }
  interface RequestFactory {
    MetadataRequest create(MetadataRequest.Callback callback);
  }
  MetadataRefresher(RequestFactory requests, CancellableScheduler scheduler, LongSupplier clock,
      Consumer<Runnable> stateSubmitter, Listener listener);
  void start();
  void refreshNow();
  void close();
}
```

- [ ] **Step 1: Write the failing tests**

Create `MetadataRefresherTest.java`. The clock and `ManualScheduler` are advanced together, the way
`HyperliquidConnectorTest` does it. The state lane is direct.

```java
package com.bookmap.plugins.layer0.hyperliquid.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.bookmap.plugins.layer0.hyperliquid.FakeHyperliquidTransport;
import com.bookmap.plugins.layer0.hyperliquid.MetadataRequest;
import com.bookmap.plugins.layer0.hyperliquid.TestMetadata;
import com.bookmap.plugins.layer0.hyperliquid.concurrent.ManualScheduler;
import com.bookmap.plugins.layer0.hyperliquid.model.Instrument;
import com.bookmap.plugins.layer0.hyperliquid.parse.HyperliquidMetaParser;
import com.bookmap.plugins.layer0.hyperliquid.transport.TransportFailure;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.junit.Test;

/** Pins when the instrument list is re-fetched: every 30 minutes and on demand after a reconnect. */
public class MetadataRefresherTest {

  private static final long INTERVAL = 1_800_000L;

  @Test
  public void firstRefreshStartsThirtyMinutesAfterStart() {
    Fixture fixture = new Fixture();
    fixture.refresher.start();

    fixture.advance(INTERVAL - 1L);
    assertEquals(0, fixture.transport.pendingHttpCount());
    fixture.advance(1L);

    assertEquals(2, fixture.transport.pendingHttpCount());
  }

  @Test
  public void nextRefreshIsThirtyMinutesAfterASuccessfulCompletion() {
    Fixture fixture = new Fixture();
    fixture.refresher.start();
    fixture.advance(INTERVAL);
    fixture.advance(5_000L);
    fixture.transport.completeMeta(200, perps("BTC", "ETH"));

    assertEquals("[[BTC, ETH]]", fixture.refreshed.toString());
    fixture.advance(INTERVAL - 1L);
    assertEquals(0, fixture.transport.pendingHttpCount());
    fixture.advance(1L);
    assertEquals(2, fixture.transport.pendingHttpCount());
  }

  @Test
  public void nextRefreshIsThirtyMinutesAfterAFailure() {
    Fixture fixture = new Fixture();
    fixture.refresher.start();
    fixture.advance(INTERVAL);
    fixture.transport.failMeta(new UnknownHostException("no dns"));

    assertEquals(1, fixture.failures.size());
    assertEquals(TransportFailure.Kind.NETWORK, fixture.failures.get(0).kind());
    assertTrue(fixture.refreshed.isEmpty());
    fixture.advance(INTERVAL);
    assertEquals(2, fixture.transport.pendingHttpCount());
  }

  @Test
  public void refreshNowFetchesImmediatelyAndReplacesThePendingTimer() {
    Fixture fixture = new Fixture();
    fixture.refresher.start();
    fixture.advance(600_000L);

    fixture.refresher.refreshNow();

    assertEquals(2, fixture.transport.pendingHttpCount());
    fixture.transport.completeMeta(200, perps("BTC"));
    // The timer armed by start() would have fired 20 minutes from here; it must be gone.
    fixture.advance(INTERVAL - 1L);
    assertEquals(0, fixture.transport.pendingHttpCount());
    fixture.advance(1L);
    assertEquals(2, fixture.transport.pendingHttpCount());
  }

  @Test
  public void refreshNowWithinSixtySecondsOfTheLastStartIsIgnored() {
    Fixture fixture = new Fixture();
    fixture.refresher.start();
    fixture.refresher.refreshNow();
    fixture.transport.completeMeta(200, perps("BTC"));

    fixture.advance(59_999L);
    fixture.refresher.refreshNow();
    assertEquals(0, fixture.transport.pendingHttpCount());

    fixture.advance(1L);
    fixture.refresher.refreshNow();
    assertEquals(2, fixture.transport.pendingHttpCount());
  }

  @Test
  public void refreshNowWhileARefreshIsInFlightIsIgnored() {
    Fixture fixture = new Fixture();
    fixture.refresher.start();
    fixture.refresher.refreshNow();
    fixture.advance(120_000L);

    fixture.refresher.refreshNow();

    assertEquals(2, fixture.transport.httpHandleCount());
  }

  @Test
  public void refreshNowBeforeStartIsIgnored() {
    Fixture fixture = new Fixture();

    fixture.refresher.refreshNow();

    assertEquals(0, fixture.transport.httpHandleCount());
  }

  @Test
  public void startIsIdempotent() {
    Fixture fixture = new Fixture();
    fixture.refresher.start();
    fixture.advance(600_000L);
    fixture.refresher.start();

    fixture.advance(1_200_000L);

    assertEquals(2, fixture.transport.httpHandleCount());
  }

  @Test
  public void closeCancelsTheTimer() {
    Fixture fixture = new Fixture();
    fixture.refresher.start();

    fixture.refresher.close();

    assertEquals(-1L, fixture.scheduler.nextDelayMillis());
    fixture.refresher.start();
    fixture.refresher.refreshNow();
    assertEquals(0, fixture.transport.httpHandleCount());
  }

  @Test
  public void closeCancelsAnInFlightRefreshAndSilencesIt() {
    Fixture fixture = new Fixture();
    fixture.refresher.start();
    fixture.refresher.refreshNow();

    fixture.refresher.close();
    fixture.transport.lateCompleteMeta(200, perps("BTC"));

    assertTrue(fixture.transport.allHttpHandlesSettled());
    assertTrue(fixture.refreshed.isEmpty());
    assertTrue(fixture.failures.isEmpty());
    assertEquals(-1L, fixture.scheduler.nextDelayMillis());
  }

  private static String perps(String... symbols) {
    return TestMetadata.allPerpMetas(TestMetadata.universe(symbols));
  }

  private static final class Fixture {
    private final FakeHyperliquidTransport transport = new FakeHyperliquidTransport();
    private final ManualScheduler scheduler = new ManualScheduler();
    private final MutableClock clock = new MutableClock();
    private final List<List<String>> refreshed = new ArrayList<List<String>>();
    private final List<TransportFailure> failures = new ArrayList<TransportFailure>();
    private final Consumer<Runnable> lane =
        new Consumer<Runnable>() {
          @Override
          public void accept(Runnable task) {
            task.run();
          }
        };
    private final MetadataRefresher refresher =
        new MetadataRefresher(
            new MetadataRefresher.RequestFactory() {
              @Override
              public MetadataRequest create(MetadataRequest.Callback callback) {
                return new MetadataRequest(
                    transport,
                    new HyperliquidMetaParser(),
                    lane,
                    URI.create("https://api.hyperliquid.xyz/info"),
                    callback);
              }
            },
            scheduler,
            clock,
            lane,
            new MetadataRefresher.Listener() {
              @Override
              public void onMetadataRefreshed(List<Instrument> instruments) {
                List<String> symbols = new ArrayList<String>();
                for (Instrument instrument : instruments) {
                  symbols.add(instrument.symbol());
                }
                refreshed.add(symbols);
              }

              @Override
              public void onMetadataRefreshFailed(TransportFailure failure) {
                failures.add(failure);
              }
            });

    private void advance(long millis) {
      clock.now += millis;
      scheduler.advanceBy(millis);
    }
  }

  private static final class MutableClock implements LongSupplier {
    private long now;

    @Override
    public long getAsLong() {
      return now;
    }
  }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test --tests '*MetadataRefresherTest'`
Expected: compilation failure, `cannot find symbol class MetadataRefresher`.

- [ ] **Step 3: Write `MetadataRefresher`**

```java
package com.bookmap.plugins.layer0.hyperliquid.session;

import com.bookmap.plugins.layer0.hyperliquid.MetadataRequest;
import com.bookmap.plugins.layer0.hyperliquid.concurrent.CancellableScheduler;
import com.bookmap.plugins.layer0.hyperliquid.model.Instrument;
import com.bookmap.plugins.layer0.hyperliquid.transport.TransportFailure;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Decides when the instrument list is fetched again: thirty minutes after the previous fetch
 * finished, and on demand after a reconnect. At most one fetch is in flight. Every method runs on
 * the state lane; the listener is called on it too.
 */
final class MetadataRefresher {

  static final long REFRESH_INTERVAL_MILLIS = 1_800_000L;

  /** Keeps a reconnect storm from turning into a REST storm. */
  static final long MIN_REFRESH_GAP_MILLIS = 60_000L;

  /** Receives the outcome of each refresh. */
  interface Listener {

    /** Reports a complete, validated replacement list. */
    void onMetadataRefreshed(List<Instrument> instruments);

    /** Reports a refresh that produced no list; the previous list stays in force. */
    void onMetadataRefreshFailed(TransportFailure failure);
  }

  /** Builds the single-use request for one refresh. */
  interface RequestFactory {

    /** Creates an unstarted request that reports to the callback. */
    MetadataRequest create(MetadataRequest.Callback callback);
  }

  private final RequestFactory requests;
  private final CancellableScheduler scheduler;
  private final LongSupplier clock;
  private final Consumer<Runnable> stateSubmitter;
  private final Listener listener;

  private CancellableScheduler.Cancellable timer;
  private MetadataRequest inFlight;
  private long lastStartedAtMillis;
  private boolean refreshedOnce;
  private boolean started;
  private boolean closed;

  MetadataRefresher(
      RequestFactory requests,
      CancellableScheduler scheduler,
      LongSupplier clock,
      Consumer<Runnable> stateSubmitter,
      Listener listener) {
    if (requests == null
        || scheduler == null
        || clock == null
        || stateSubmitter == null
        || listener == null) {
      throw new IllegalArgumentException("refresher dependencies must not be null");
    }
    this.requests = requests;
    this.scheduler = scheduler;
    this.clock = clock;
    this.stateSubmitter = stateSubmitter;
    this.listener = listener;
  }

  /** Arms the first timer. Login has just fetched the list, so nothing is fetched here. */
  void start() {
    if (started || closed) {
      return;
    }
    started = true;
    scheduleNext();
  }

  /** Fetches now unless a fetch is in flight or one started less than a minute ago. */
  void refreshNow() {
    if (!started || closed || inFlight != null) {
      return;
    }
    if (refreshedOnce && clock.getAsLong() - lastStartedAtMillis < MIN_REFRESH_GAP_MILLIS) {
      return;
    }
    refresh();
  }

  /** Cancels the timer and any fetch in flight; the listener is not called afterwards. */
  void close() {
    closed = true;
    cancelTimer();
    if (inFlight != null) {
      inFlight.cancel();
      inFlight = null;
    }
  }

  private void refresh() {
    cancelTimer();
    lastStartedAtMillis = clock.getAsLong();
    refreshedOnce = true;
    // Set before start(): a transport that throws reports its failure synchronously.
    inFlight =
        requests.create(
            new MetadataRequest.Callback() {
              @Override
              public void onInstruments(List<Instrument> instruments) {
                finished();
                listener.onMetadataRefreshed(instruments);
              }

              @Override
              public void onFailure(TransportFailure failure) {
                finished();
                listener.onMetadataRefreshFailed(failure);
              }
            });
    inFlight.start();
  }

  /** A cancelled request never calls back, so reaching here means the refresher is still open. */
  private void finished() {
    inFlight = null;
    scheduleNext();
  }

  private void scheduleNext() {
    cancelTimer();
    timer =
        scheduler.schedule(
            new Runnable() {
              @Override
              public void run() {
                stateSubmitter.accept(
                    new Runnable() {
                      @Override
                      public void run() {
                        timer = null;
                        if (!closed && inFlight == null) {
                          refresh();
                        }
                      }
                    });
              }
            },
            REFRESH_INTERVAL_MILLIS);
  }

  private void cancelTimer() {
    if (timer != null) {
      timer.cancel();
      timer = null;
    }
  }
}
```

Implementation note on the synchronous-failure path: when `inFlight.start()` fails synchronously,
`finished()` runs *inside* `start()` and sets `inFlight = null` before the assignment expression
`inFlight = requests.create(...)` has anything left to do — the assignment already happened on the
previous statement, so the order above is correct. Do not fold `create` and `start` into one
expression.

- [ ] **Step 4: Run the tests**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test --tests '*MetadataRefresherTest'`
Expected: 10 tests pass.

- [ ] **Step 5: Run the gate and commit**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline spotlessApply check verifyJava8Bytecode`
Expected: `BUILD SUCCESSFUL`. SpotBugs runs at LOW confidence; if it flags the unused-until-Task-5
class, that is not expected (package-private classes with tests are not reported) — read the HTML
report under `build/reports/spotbugs` rather than adding an exclusion.

```bash
git add -A src
git commit -m "feat: add the metadata refresher's timer and reconnect trigger"
```

---

### Task 5: The session runs the refresher and applies its results

**Files:**
- Create: `src/main/java/com/bookmap/plugins/layer0/hyperliquid/session/MetadataRequestFactory.java`
- Modify: `src/main/java/com/bookmap/plugins/layer0/hyperliquid/session/HyperliquidSession.java`
- Modify: `src/main/java/com/bookmap/plugins/layer0/hyperliquid/Provider.java` (`ProductionSessionFactory.create`)
- Modify (constructor argument only): `ProviderEndToEndTest.java`, and in `session/`:
  `AssetContextFeedTest.java`, `HyperliquidSessionAssetContextTest.java`,
  `HyperliquidSessionDeltaBookTest.java`, `HyperliquidSessionLifecycleTest.java`,
  `HyperliquidSessionSubscriptionTest.java`, `HyperliquidSessionTickSizeTest.java`
- Test: `src/test/java/com/bookmap/plugins/layer0/hyperliquid/session/HyperliquidSessionMetadataRefreshTest.java`

**Interfaces:**
- Consumes: `MetadataRefresher`, `MetadataRefresher.Listener`, `MetadataRefresher.RequestFactory`
  (Task 4); `MetadataRequest` (Task 2).
- Produces:

```java
// session package, public
public interface MetadataRequestFactory {
  MetadataRequest create(SourceProfile profile, MetadataRequest.Callback callback);
}
```

  and a new `HyperliquidSession` constructor parameter, inserted **after**
  `AssetContextConnectorFactory assetContextConnectorFactory` and before `Runnable afterClose`:
  `MetadataRequestFactory metadataRequestFactory`.

- [ ] **Step 1: Write the failing tests**

Create `HyperliquidSessionMetadataRefreshTest.java`. It reuses the fixture shape of
`HyperliquidSessionLifecycleTest` (queued executor, clock-driven scheduler), reduced to what these
cases need.

```java
package com.bookmap.plugins.layer0.hyperliquid.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.bookmap.plugins.layer0.hyperliquid.FakeHyperliquidTransport;
import com.bookmap.plugins.layer0.hyperliquid.HyperliquidConnector;
import com.bookmap.plugins.layer0.hyperliquid.HyperliquidEnvironment;
import com.bookmap.plugins.layer0.hyperliquid.MarketDataSource;
import com.bookmap.plugins.layer0.hyperliquid.MetadataRequest;
import com.bookmap.plugins.layer0.hyperliquid.SourceProfile;
import com.bookmap.plugins.layer0.hyperliquid.TestMetadata;
import com.bookmap.plugins.layer0.hyperliquid.budget.HyperliquidProcessBudget;
import com.bookmap.plugins.layer0.hyperliquid.concurrent.CancellableScheduler;
import com.bookmap.plugins.layer0.hyperliquid.concurrent.StateEventDispatcher;
import com.bookmap.plugins.layer0.hyperliquid.model.SubscriptionType;
import com.bookmap.plugins.layer0.hyperliquid.parse.HyperliquidMessageParser;
import com.bookmap.plugins.layer0.hyperliquid.parse.HyperliquidMetaParser;
import java.lang.reflect.Constructor;
import java.net.UnknownHostException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import org.junit.Test;

/** Session behavior when the instrument list is fetched again during a session. */
public class HyperliquidSessionMetadataRefreshTest {

  private static final long INTERVAL = 1_800_000L;

  @Test
  public void firstConnectionArmsTheThirtyMinuteRefresh() {
    Fixture fixture = new Fixture();
    fixture.login("BTC");
    assertEquals(2, fixture.transport.httpHandleCount());

    fixture.advance(INTERVAL);

    assertEquals(4, fixture.transport.httpHandleCount());
    assertEquals(2, fixture.transport.pendingHttpCount());
  }

  @Test
  public void reconnectRefreshesImmediately() {
    Fixture fixture = new Fixture();
    fixture.login("BTC");

    fixture.reconnect();

    assertEquals(2, fixture.transport.pendingHttpCount());
  }

  @Test
  public void refreshedListIsRepublishedWithKnownMarkPrices() {
    Fixture fixture = new Fixture();
    fixture.login("BTC");
    fixture.session.onFrame(1L, TestMetadata.assetContextsFrame("{\"BTC\":{\"markPx\":\"100.5\"}}"));
    fixture.drain();

    fixture.advance(INTERVAL);
    fixture.transport.completeMeta(200, perps("BTC", "NEW"));
    fixture.drain();

    assertEquals("known:2", fixture.sink.events().get(fixture.sink.events().size() - 1));
    assertEquals("100.5", fixture.sink.lastReferencePrice("BTC"));
    assertEquals(null, fixture.sink.lastReferencePrice("NEW"));
  }

  @Test
  public void aNewlyListedInstrumentCanBeSubscribed() {
    Fixture fixture = new Fixture();
    fixture.login("BTC");
    fixture.advance(INTERVAL);
    fixture.transport.completeMeta(200, perps("BTC", "NEW"));
    fixture.drain();

    fixture.session.subscribe("NEW", "", "PERPETUAL");
    fixture.drain();

    assertEquals(0, count(fixture.sink.events(), "not-found:NEW"));
    fixture.completeAllSends();
    assertTrue(
        fixture.transport.socket().successfulSendBodies().toString(),
        fixture.transport.socket().successfulSendBodies().toString().contains("\"coin\":\"NEW\""));
  }

  @Test
  public void failedRefreshKeepsTheListAndOnlyLogs() {
    Fixture fixture = new Fixture();
    fixture.login("BTC");
    int publishedBefore = count(fixture.sink.events(), "known:1");

    fixture.advance(INTERVAL);
    fixture.transport.failMeta(new UnknownHostException("no dns"));
    fixture.drain();

    assertEquals(1, count(fixture.sink.events(), "diagnostic:metadata refresh failed: no dns"));
    assertEquals(publishedBefore, count(fixture.sink.events(), "known:1"));
    assertTrue(fixture.sink.systemMessages().isEmpty());
    assertEquals(0, count(fixture.sink.events(), "connection-lost"));
    fixture.session.subscribe("BTC", "", "PERPETUAL");
    fixture.drain();
    assertEquals(0, count(fixture.sink.events(), "not-found:BTC"));
  }

  @Test
  public void closeStopsTheRefresherAndIgnoresALateResult() {
    Fixture fixture = new Fixture();
    fixture.login("BTC");
    fixture.advance(INTERVAL);
    int eventsBefore = fixture.sink.events().size();

    fixture.session.close();
    fixture.drain();
    fixture.transport.lateCompleteMeta(200, perps("BTC", "NEW"));
    fixture.drain();

    assertTrue(fixture.transport.allHttpHandlesSettled());
    assertEquals(0, count(fixture.sink.events().subList(eventsBefore, fixture.sink.events().size()), "known:"));
  }

  @Test
  public void closingBeforeLoginDoesNotFail() {
    Fixture fixture = new Fixture();

    fixture.session.close();
    fixture.drain();

    assertEquals(0, fixture.transport.httpHandleCount());
  }

  static String perps(String... symbols) {
    return TestMetadata.allPerpMetas(TestMetadata.universe(symbols));
  }

  /** A private budget: the JVM-wide one would leak permits between test classes. */
  static HyperliquidProcessBudget budget() {
    try {
      Constructor<HyperliquidProcessBudget> constructor =
          HyperliquidProcessBudget.class.getDeclaredConstructor(
              Integer.TYPE, Integer.TYPE, Integer.TYPE, Integer.TYPE, Long.TYPE);
      constructor.setAccessible(true);
      return constructor.newInstance(10, 30, 2_000, 1_000, 60_000L);
    } catch (Exception failure) {
      throw new AssertionError(failure);
    }
  }

  static int count(List<String> events, String prefix) {
    int matches = 0;
    for (String event : events) {
      if (event.startsWith(prefix)) {
        matches++;
      }
    }
    return matches;
  }

  static final class Fixture {
    private final ManualExecutor executor = new ManualExecutor();
    private final Clock clock = new Clock();
    private final Scheduler scheduler = new Scheduler(clock);
    private final HyperliquidProcessBudget budget = budget();
    private final FakeHyperliquidTransport transport = new FakeHyperliquidTransport();
    private final RecordingSessionSink sink = new RecordingSessionSink();
    private final StateEventDispatcher dispatcher =
        new StateEventDispatcher(
            executor,
            4_096,
            new Runnable() {
              @Override
              public void run() {
                // overflow is not exercised here
              }
            });
    private final HyperliquidConnector connector =
        new HyperliquidConnector(
            transport, new HyperliquidMetaParser(), budget, scheduler, clock, dispatcher::submitControl);
    private final HyperliquidSession session =
        new HyperliquidSession(
            connector,
            new HyperliquidMessageParser(),
            budget,
            scheduler,
            clock,
            dispatcher,
            sink,
            new AssetContextConnectorFactory() {
              @Override
              public HyperliquidConnector create() {
                throw new AssertionError("this fixture uses the Hyperliquid source only");
              }
            },
            new MetadataRequestFactory() {
              @Override
              public MetadataRequest create(
                  SourceProfile profile, MetadataRequest.Callback callback) {
                return new MetadataRequest(
                    transport,
                    new HyperliquidMetaParser(),
                    dispatcher::submitControl,
                    profile.infoUri(),
                    callback);
              }
            },
            new Runnable() {
              @Override
              public void run() {
                // no-op
              }
            });
    private long generation = 1L;

    void login(String... symbols) {
      session.login(SourceProfile.of(MarketDataSource.HYPERLIQUID, HyperliquidEnvironment.MAINNET));
      drain();
      transport.completeMeta(200, perps(symbols));
      drain();
      transport.openSocket();
      drain();
      transport.socket().succeedNextSend(); // fastAssetCtxs
      drain();
    }

    void reconnect() {
      transport.remoteClose(1006, "lost");
      drain();
      advance(1_000L);
      transport.openSocket();
      generation++;
      drain();
      transport.socket().succeedNextSend(); // fastAssetCtxs
      drain();
    }

    void activate(String coin) {
      session.subscribe(coin, "", "PERPETUAL");
      drain();
      completeAllSends();
      session.onFrame(
          generation,
          "{\"channel\":\"l2Book\",\"data\":{\"coin\":\""
              + coin
              + "\",\"time\":1,\"levels\":[[{\"px\":\"100.000\",\"sz\":\"1\"}],[]]}}");
      ack(coin, SubscriptionType.L2_BOOK);
      ack(coin, SubscriptionType.TRADES);
      drain();
    }

    /**
     * Completes every queued send. After a 30-minute advance the queue also holds heartbeat pings;
     * completing one arms the 15-second pong deadline, so a pong is delivered straight away.
     */
    void completeAllSends() {
      while (transport.socket().pendingSendCount() > 0) {
        transport.socket().succeedNextSend();
        drain();
      }
      session.onFrame(generation, "{\"channel\":\"pong\"}");
      drain();
    }

    void ack(String coin, SubscriptionType type) {
      session.onFrame(
          generation,
          "{\"channel\":\"subscriptionResponse\",\"data\":{\"method\":\"subscribe\","
              + "\"subscription\":{\"type\":\""
              + type.wireName()
              + "\",\"coin\":\""
              + coin
              + "\"}}}");
    }

    void refreshTo(String... symbols) {
      advance(INTERVAL);
      transport.completeMeta(200, perps(symbols));
      drain();
    }

    void advance(long millis) {
      scheduler.advanceBy(millis);
      drain();
    }

    void drain() {
      executor.drain();
    }
  }

  static final class ManualExecutor implements Executor {
    private final ArrayDeque<Runnable> tasks = new ArrayDeque<Runnable>();

    @Override
    public void execute(Runnable command) {
      tasks.addLast(command);
    }

    void drain() {
      while (!tasks.isEmpty()) {
        tasks.removeFirst().run();
      }
    }
  }

  static final class Clock implements LongSupplier {
    private long now;

    @Override
    public long getAsLong() {
      return now;
    }
  }

  static final class Scheduler implements CancellableScheduler {
    private final Clock clock;
    private final List<Task> tasks = new ArrayList<Task>();

    Scheduler(Clock clock) {
      this.clock = clock;
    }

    @Override
    public Cancellable schedule(Runnable task, long delayMillis) {
      Task scheduled = new Task(task, clock.now + Math.max(0L, delayMillis));
      tasks.add(scheduled);
      return scheduled;
    }

    /** Runs due tasks in due-time order, moving the clock to each task's due time. */
    void advanceBy(long millis) {
      long target = clock.now + millis;
      while (true) {
        Task due = null;
        for (Task task : tasks) {
          if (!task.cancelled && task.due <= target && (due == null || task.due < due.due)) {
            due = task;
          }
        }
        if (due == null) {
          clock.now = target;
          return;
        }
        tasks.remove(due);
        clock.now = Math.max(clock.now, due.due);
        due.task.run();
      }
    }

    private static final class Task implements Cancellable {
      private final Runnable task;
      private final long due;
      private boolean cancelled;

      private Task(Runnable task, long due) {
        this.task = task;
        this.due = due;
      }

      @Override
      public void cancel() {
        cancelled = true;
      }
    }
  }
}
```

Helpers this file relies on, all verified to exist: `RecordingSessionSink.events()`,
`systemMessages()`, `lastReferencePrice(String)`; `FakeSocket.pendingSendCount()`,
`succeedNextSend()`, `successfulSendBodies()`.

Heartbeats: the connector pings every 30 s, so a 30-minute advance queues pings on the fake socket.
An uncompleted ping arms no pong deadline, so the tests that only advance and complete HTTP are
unaffected. `completeAllSends()` is the one place that completes pings, and it answers with a pong.

- [ ] **Step 2: Run it and watch it fail**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test --tests '*HyperliquidSessionMetadataRefreshTest'`
Expected: compilation failure, `cannot find symbol class MetadataRequestFactory`.

- [ ] **Step 3: Create `MetadataRequestFactory`**

```java
package com.bookmap.plugins.layer0.hyperliquid.session;

import com.bookmap.plugins.layer0.hyperliquid.MetadataRequest;
import com.bookmap.plugins.layer0.hyperliquid.SourceProfile;

/** Construction seam for the metadata requests a session issues after login. */
public interface MetadataRequestFactory {

  /**
   * Creates an unstarted request against the profile's info endpoint, sharing the session's
   * transport and state lane.
   */
  MetadataRequest create(SourceProfile profile, MetadataRequest.Callback callback);
}
```

- [ ] **Step 4: Wire the session**

All edits are in `HyperliquidSession.java`; make each with a targeted `Edit`.

1. Class declaration: add the listener interface.

```java
public final class HyperliquidSession
    implements HyperliquidSessionApi, HyperliquidConnector.Listener, MetadataRefresher.Listener {
```

2. Fields: after `private final AssetContextConnectorFactory assetContextConnectorFactory;` add
   `private final MetadataRequestFactory metadataRequestFactory;`, and after
   `private AssetContextFeed assetContextFeed;` add `private MetadataRefresher metadataRefresher;`.

3. Constructor: add the parameter `MetadataRequestFactory metadataRequestFactory` after
   `assetContextConnectorFactory`, add `|| metadataRequestFactory == null` to the null check, and
   assign the field.

4. `handleLogin`: create the refresher when the profile is fixed.

```java
      if (profile == null) {
        profile = newProfile;
        dataHealth.start(newProfile);
        metadataRefresher =
            new MetadataRefresher(
                new MetadataRefresher.RequestFactory() {
                  @Override
                  public MetadataRequest create(MetadataRequest.Callback callback) {
                    return metadataRequestFactory.create(profile, callback);
                  }
                },
                scheduler,
                clock,
                dispatcher::submitControl,
                this);
      }
```

5. `onSocketOpened`: in the `if (reconnecting)` branch add `metadataRefresher.refreshNow();` as its
   first statement; in the `else` branch add `metadataRefresher.start();` after
   `connectedOnce = true;`. (`onSocketOpened` cannot run before `handleLogin`, which is what starts
   the connector, so the field is non-null here.)

6. Share the list replacement. Replace the body of `onMetadata` between the `closed` check and
   `startAssetContextFeedIfNeeded()`:

```java
  @Override
  public void onMetadata(List<Instrument> metadata) {
    if (closed) {
      return;
    }
    replaceInstruments(metadata);
    metadataReceived = true;
    sink.onKnownInstruments(new ArrayList<Instrument>(instruments.values()));
    startAssetContextFeedIfNeeded();
  }

  /** Receives a refreshed instrument list on the state lane. */
  @Override
  public void onMetadataRefreshed(List<Instrument> metadata) {
    if (closed) {
      return;
    }
    replaceInstruments(metadata);
    sink.onKnownInstruments(new ArrayList<Instrument>(instruments.values()));
  }

  /** A failed refresh is not an incident: the previous list stays in force until the next one. */
  @Override
  public void onMetadataRefreshFailed(TransportFailure failure) {
    if (!closed) {
      sink.onDiagnostic("metadata refresh failed: " + failure.message());
    }
  }

  /** Replaces the universe, carrying over every mark price already known. */
  private void replaceInstruments(List<Instrument> metadata) {
    instruments.clear();
    knownCoins.clear();
    for (Instrument instrument : metadata) {
      instruments.put(
          instrument.symbol(),
          instrument.withReferencePrice(assetContexts.markPrice(instrument.coin())));
      knownCoins.add(instrument.coin());
    }
  }
```

   At login `assetContexts` is empty, so `markPrice` returns null. `Instrument.withReferencePrice`
   simply copies the instrument with the given price (null included), and a parsed instrument has no
   reference price yet, so login publishes exactly what it publishes today.

7. `stop`: next to the asset-context feed shutdown add

```java
    if (metadataRefresher != null) {
      metadataRefresher.close();
      metadataRefresher = null;
    }
```

   The null check covers a session closed before login.

8. Import `com.bookmap.plugins.layer0.hyperliquid.MetadataRequest`.

- [ ] **Step 5: Pass the new argument everywhere a session is built**

`grep -rn "new HyperliquidSession(" src` lists 8 sites. In `Provider.ProductionSessionFactory.create`
add, after the `AssetContextConnectorFactory` lambda:

```java
      MetadataRequestFactory metadataRequestFactory =
          (profile, callback) ->
              new MetadataRequest(
                  transport,
                  new HyperliquidMetaParser(),
                  dispatcher::submitControl,
                  profile.infoUri(),
                  callback);
```

and pass `metadataRequestFactory` after `assetContextConnectorFactory`. Import
`com.bookmap.plugins.layer0.hyperliquid.session.MetadataRequestFactory`.

In each of the 7 test sites, insert after the `AssetContextConnectorFactory` argument the same
anonymous class the new test file uses, built on that fixture's own `transport` and `dispatcher`
(variable names differ per file — read each fixture):

```java
            new MetadataRequestFactory() {
              @Override
              public MetadataRequest create(
                  SourceProfile profile, MetadataRequest.Callback callback) {
                return new MetadataRequest(
                    transport,
                    new HyperliquidMetaParser(),
                    dispatcher::submitControl,
                    profile.infoUri(),
                    callback);
              }
            },
```

- [ ] **Step 6: Run the whole suite**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test`
Expected: all green, including the 7 new cases.

Known interaction to check if an *existing* test fails: a test that advances its scheduler by 30
minutes or more after login now has two pending metadata POSTs and, in
`ProviderEndToEndTest` line ~982, asserts that every HTTP handle is settled after close.
`metadataRefresher.close()` cancels them, which settles them; if such an assertion fails, the
defect is in `stop()` ordering, not in the test.

- [ ] **Step 7: Run the gate and commit**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline spotlessApply check verifyJava8Bytecode`
Expected: `BUILD SUCCESSFUL`.

```bash
git add -A src
git commit -m "feat: refresh instrument metadata every 30 minutes and after a reconnect"
```

---

### Task 6: Report a subscribed instrument that left the list

**Files:**
- Modify: `src/main/java/com/bookmap/plugins/layer0/hyperliquid/session/HyperliquidSession.java`
- Test: `src/test/java/com/bookmap/plugins/layer0/hyperliquid/session/HyperliquidSessionMetadataRefreshTest.java`

**Interfaces:**
- Consumes: `onMetadataRefreshed` and the `Fixture` helpers `activate`, `refreshTo` (Task 5);
  `SubscriptionRecord.alias()`, `SubscriptionRecord.state()`, `sink.onSystemMessage(String, MessageKind)`.
- Produces: no new API.

- [ ] **Step 1: Write the failing tests**

Add to `HyperliquidSessionMetadataRefreshTest`. `RecordingSessionSink.systemMessages()` records
`kind + ":" + message`.

```java
  private static final String SUFFIX =
      " no longer listed by Hyperliquid; open subscriptions stay active until removed";

  @Test
  public void unlistedSubscriptionStaysOpenAndIsReportedOnce() {
    Fixture fixture = new Fixture();
    fixture.login("BTC", "ETH");
    fixture.activate("ETH");

    fixture.refreshTo("BTC");

    assertEquals("[UNCLASSIFIED:ETH" + SUFFIX + "]", fixture.sink.systemMessages().toString());
    assertEquals(0, count(fixture.sink.events(), "instrument-removed:ETH"));
    // The subscription still publishes.
    fixture.session.onFrame(
        fixture.generation,
        "{\"channel\":\"l2Book\",\"data\":{\"coin\":\"ETH\",\"time\":2,"
            + "\"levels\":[[{\"px\":\"101.000\",\"sz\":\"1\"}],[]]}}");
    fixture.drain();
    assertTrue(count(fixture.sink.events(), "depth:ETH") >= 2);

    fixture.refreshTo("BTC");
    assertEquals(1, fixture.sink.systemMessages().size());
  }

  @Test
  public void instrumentsUnlistedTogetherShareOneSortedMessage() {
    Fixture fixture = new Fixture();
    fixture.login("BTC", "SOL", "ETH");
    fixture.activate("SOL");
    fixture.activate("ETH");

    fixture.refreshTo("BTC");

    assertEquals("[UNCLASSIFIED:ETH, SOL" + SUFFIX + "]", fixture.sink.systemMessages().toString());
  }

  @Test
  public void relistingRearmsTheReport() {
    Fixture fixture = new Fixture();
    fixture.login("BTC", "ETH");
    fixture.activate("ETH");
    fixture.refreshTo("BTC");

    fixture.refreshTo("BTC", "ETH");
    assertEquals(1, fixture.sink.systemMessages().size());
    fixture.refreshTo("BTC");

    assertEquals(2, fixture.sink.systemMessages().size());
  }

  @Test
  public void unsubscribingRearmsTheReport() {
    Fixture fixture = new Fixture();
    fixture.login("BTC", "ETH");
    fixture.activate("ETH");
    fixture.refreshTo("BTC");
    fixture.session.unsubscribe("ETH");
    fixture.drain();

    fixture.refreshTo("BTC", "ETH");
    fixture.activate("ETH");
    fixture.refreshTo("BTC");

    assertEquals(2, fixture.sink.systemMessages().size());
  }

  @Test
  public void unlistedInstrumentCannotBeSubscribedAgain() {
    Fixture fixture = new Fixture();
    fixture.login("BTC", "ETH");
    fixture.refreshTo("BTC");

    fixture.session.subscribe("ETH", "", "PERPETUAL");
    fixture.drain();

    assertEquals(1, count(fixture.sink.events(), "not-found:ETH"));
    assertTrue(fixture.sink.systemMessages().isEmpty());
  }
```

- [ ] **Step 2: Run them and watch them fail**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test --tests '*HyperliquidSessionMetadataRefreshTest'`
Expected: the first four new tests fail on the system-message assertions (`expected:<[UNCLASSIFIED:ETH ...]> but was:<[]>`).
`unlistedInstrumentCannotBeSubscribedAgain` already passes — it pins Task 5's behavior from the
user's side and stays as a regression test.

- [ ] **Step 3: Implement the report**

In `HyperliquidSession.java`:

1. Field, beside `knownCoins`:

```java
  private final Set<String> unlistedNotified = new HashSet<String>();
```

2. Call it from `onMetadataRefreshed`, after `sink.onKnownInstruments(...)`:

```java
    reportUnlistedSubscriptions();
```

3. The method (uses `java.util.TreeSet`; add the import):

```java
  /**
   * Reports, once per listing, the open subscriptions whose instrument left the list. The
   * subscription is left alone: an exchange can keep publishing a delisted book for a while, and
   * {@code BOOK_STALE} already covers a feed that goes quiet.
   */
  private void reportUnlistedSubscriptions() {
    unlistedNotified.retainAll(unlistedAliases());
    TreeSet<String> unreported = new TreeSet<String>(unlistedAliases());
    unreported.removeAll(unlistedNotified);
    if (unreported.isEmpty()) {
      return;
    }
    unlistedNotified.addAll(unreported);
    StringBuilder message = new StringBuilder();
    for (String alias : unreported) {
      if (message.length() > 0) {
        message.append(", ");
      }
      message.append(alias);
    }
    message.append(
        " no longer listed by Hyperliquid; open subscriptions stay active until removed");
    sink.onSystemMessage(message.toString(), MessageKind.UNCLASSIFIED);
  }

  private Set<String> unlistedAliases() {
    Set<String> aliases = new HashSet<String>();
    for (SubscriptionRecord record : records.values()) {
      if (record.state() != SubscriptionRecord.State.REMOVED
          && !instruments.containsKey(record.alias())) {
        aliases.add(record.alias());
      }
    }
    return aliases;
  }
```

   `retainAll` drops an alias that is listed again *or* no longer subscribed, which covers the
   spec's relisting rule in one place.

4. In `removeRecord`, right after `dataHealth.remove(record.alias());`:

```java
    unlistedNotified.remove(record.alias());
```

   This is what re-arms the report when the user unsubscribes while the instrument is still
   unlisted and a later listing/unlisting cycle happens between two refreshes.

- [ ] **Step 4: Run the tests**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test --tests '*HyperliquidSessionMetadataRefreshTest'`
Expected: 12 tests pass.

- [ ] **Step 5: Run the gate and commit**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline spotlessApply check verifyJava8Bytecode`
Expected: `BUILD SUCCESSFUL`.

```bash
git add -A src
git commit -m "feat: report a subscribed instrument that is no longer listed"
```

---

### Task 7: End-to-end case and documentation

**Files:**
- Modify: `src/test/java/com/bookmap/plugins/layer0/hyperliquid/ProviderEndToEndTest.java`
- Modify: `README.md` (Scope section), `docs/development.md` (package table)

**Interfaces:**
- Consumes: everything above, through `Provider`.
- Produces: nothing new.

- [ ] **Step 1: Write the test**

The fixture's helpers, verified: `loginWithMetadata(String...)`, `advance(long)` (moves clock and
scheduler, then drains), `drain()`, `subscribe(String)`, `completeSends()` (loops until the fake
socket has no pending send), `ack(String coin, String type)`, `book(coin, time, bidPx, bidSz, askPx,
askSz)`, `provider`, `transport`, and `data.depths` (entries `depth:<alias>:<price>:<size>`).
`completeSends()` also completes queued heartbeat pings, which arms the pong deadline; deliver a
pong the way the existing heartbeat case in this file does (search for `"pong"`) before advancing
time again. This case does not advance after subscribing, so it needs no pong.

```java
  @Test
  public void instrumentListedDuringTheSessionBecomesSubscribable() {
    Fixture fixture = new Fixture(budget(2, 20, 50, 2));
    fixture.loginWithMetadata("BTC");
    assertFalse(knownSymbols(fixture).contains("NEW"));

    fixture.advance(1_800_000L);
    fixture.transport.completeMeta(
        200, TestMetadata.allPerpMetas(TestMetadata.universe("BTC", "NEW")));
    fixture.drain();

    assertTrue(knownSymbols(fixture).contains("NEW"));
    fixture.subscribe("NEW");
    fixture.completeSends();
    fixture.ack("NEW", "l2Book");
    fixture.ack("NEW", "trades");
    fixture.book("NEW", 1L, "100", "1", "101", "2");
    fixture.drain();
    assertTrue(fixture.data.depths.toString(), fixture.data.depths.toString().contains("depth:NEW:"));
  }

  private static List<String> knownSymbols(Fixture fixture) {
    List<String> symbols = new ArrayList<String>();
    for (SubscribeInfo info : fixture.provider.getSupportedFeatures().knownInstruments) {
      symbols.add(info.symbol);
    }
    return symbols;
  }
```

If `loginWithMetadata` leaves the fixture in a state where `fixture.data` is not yet attached, use
the same listener-registration call the first test in the file makes after constructing `Fixture`.

- [ ] **Step 2: Run it**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test --tests '*ProviderEndToEndTest'`
Expected: pass on the first run — Tasks 5 and 6 already implement the behavior, and this case
guards the `Provider` wiring (`knownSubscribeInfo` replacement) that no session-level test sees. To
prove the test can fail, temporarily comment out the `sink.onKnownInstruments` call in
`onMetadataRefreshed`, watch `assertTrue(knownSymbols(fixture).contains("NEW"))` fail, and restore
it.

- [ ] **Step 3: Update `README.md`**

In the `## Scope` section, after the "Supported:" paragraph, add:

```markdown
The instrument list is fetched again every 30 minutes and after every reconnect, so a market listed
during a session shows up in the Subscribe dialog without logging in again. If an instrument you
are subscribed to leaves the list, the adapter says so once in a system message and keeps the
subscription open until you remove it; `BOOK_STALE` reports it if the exchange stops publishing. A
failed refresh keeps the previous list and is only logged.
```

- [ ] **Step 4: Update `docs/development.md`**

In the package table, change two rows:

```markdown
| `hyperliquid` | Layer 0 entry point: provider, connectivity fields, source selection, connection lifecycle, the instrument-metadata request |
```

```markdown
| `hyperliquid.session` | Bridging to Bookmap session output: subscription state, data-health monitoring, the periodic metadata refresh, and the extra mark-price connection a relay source needs |
```

- [ ] **Step 5: Run the gate and commit**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline spotlessApply check verifyJava8Bytecode`
Expected: `BUILD SUCCESSFUL`.

```bash
git add -A src README.md docs/development.md
git commit -m "docs: describe the periodic instrument-list refresh"
```

- [ ] **Step 6: Mark the spec implemented**

Change the spec's status line to `- 状態: spec-review READY、人間の承認済み、実装済み` and commit:

```bash
git add docs/superpowers/specs/2026-09-19-metadata-refresh-design.md
git commit -m "docs: mark the metadata refresh spec as implemented"
```

---

## Verification after the last task

- `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline clean build` succeeds and
  produces `build/libs/hyperliquid-adapter-1.4.0.jar` (the version bump is out of scope).
- `git diff 901b688 -- src/test/java/com/bookmap/plugins/layer0/hyperliquid/HyperliquidConnectorTest.java`
  prints nothing.
- `wc -l src/main/java/com/bookmap/plugins/layer0/hyperliquid/HyperliquidConnector.java` is below
  the 1,091 lines it started at.
- Not verifiable here, left for a Bookmap session (spec, "実装後に実機で確認すべき事項"): that the
  Subscribe dialog picks up a list that grew mid-session.
