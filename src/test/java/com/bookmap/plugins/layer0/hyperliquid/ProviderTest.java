package com.bookmap.plugins.layer0.hyperliquid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.bookmap.plugins.layer0.hyperliquid.model.DepthUpdate;
import com.bookmap.plugins.layer0.hyperliquid.model.PerpetualInstrument;
import com.bookmap.plugins.layer0.hyperliquid.session.ConnectionFailure;
import com.bookmap.plugins.layer0.hyperliquid.session.HyperliquidSessionApi;
import com.bookmap.plugins.layer0.hyperliquid.session.LoginFailure;
import com.bookmap.plugins.layer0.hyperliquid.session.MessageKind;
import com.bookmap.plugins.layer0.hyperliquid.session.SessionSink;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import velox.api.layer0.annotations.Layer0CredentialsFieldsManager;
import velox.api.layer0.annotations.Layer0LiveModule;
import velox.api.layer0.credentialscomponents.CredentialsSerializationField;
import velox.api.layer1.Layer1ApiAdminAdapter;
import velox.api.layer1.Layer1ApiDataAdapter;
import velox.api.layer1.Layer1ApiInstrumentAdapter;
import velox.api.layer1.annotations.Layer1ApiVersion;
import velox.api.layer1.annotations.Layer1ApiVersionValue;
import velox.api.layer1.common.Log;
import velox.api.layer1.data.DefaultAndList;
import velox.api.layer1.data.DisconnectionReason;
import velox.api.layer1.data.ExtendedLoginData;
import velox.api.layer1.data.InstrumentInfo;
import velox.api.layer1.data.Layer1ApiProviderSupportedFeatures;
import velox.api.layer1.data.LoginData;
import velox.api.layer1.data.LoginFailedReason;
import velox.api.layer1.data.OrderSendParameters;
import velox.api.layer1.data.OrderUpdateParameters;
import velox.api.layer1.data.SubscribeInfo;
import velox.api.layer1.data.SubscribeInfoCrypto;
import velox.api.layer1.data.SystemTextMessageType;
import velox.api.layer1.data.TradeInfo;

/** Tests the Bookmap-facing adapter contract without starting network transport. */
public class ProviderTest {

  /** Prevents unspecified and malformed login data from selecting the test network. */
  @Test
  public void loginSelectsTestnetOnlyForTrueExtendedField() {
    FakeSessionFactory factory = new FakeSessionFactory();
    Provider provider = new Provider(factory);

    provider.login(
        new ExtendedLoginData(Collections.<String, CredentialsSerializationField>emptyMap()));
    assertEquals(HyperliquidEnvironment.MAINNET, factory.session.lastLoginProfile.environment());
    provider.login(new PlainLoginData());
    assertEquals(HyperliquidEnvironment.MAINNET, factory.session.lastLoginProfile.environment());
    provider.login(new ExtendedLoginData(Collections.singletonMap("testnet", field("true"))));
    assertEquals(HyperliquidEnvironment.TESTNET, factory.session.lastLoginProfile.environment());
    provider.login(new ExtendedLoginData(Collections.singletonMap("testnet", field("false"))));
    assertEquals(HyperliquidEnvironment.MAINNET, factory.session.lastLoginProfile.environment());
  }

  /** The dropdown picks the source; testnet only matters for Hyperliquid; unknown falls back. */
  @Test
  public void loginResolvesSourceFromDropdownAndFallsBackToHyperliquid() {
    FakeSessionFactory factory = new FakeSessionFactory();
    Provider provider = new Provider(factory);
    Map<String, CredentialsSerializationField> fields =
        new HashMap<String, CredentialsSerializationField>();

    fields.put("source", field("Borsa"));
    fields.put("testnet", field("true"));
    provider.login(new ExtendedLoginData(fields));
    assertEquals(MarketDataSource.BORSA, factory.session.lastLoginProfile.source());
    assertEquals(HyperliquidEnvironment.MAINNET, factory.session.lastLoginProfile.environment());

    fields.put("source", field("Hyperdash"));
    provider.login(new ExtendedLoginData(fields));
    assertEquals(MarketDataSource.HYPERDASH, factory.session.lastLoginProfile.source());

    fields.put("source", field("something-else"));
    provider.login(new ExtendedLoginData(fields));
    assertEquals(MarketDataSource.HYPERLIQUID, factory.session.lastLoginProfile.source());
    assertEquals(HyperliquidEnvironment.TESTNET, factory.session.lastLoginProfile.environment());

    fields.remove("source");
    provider.login(new ExtendedLoginData(fields));
    assertEquals(MarketDataSource.HYPERLIQUID, factory.session.lastLoginProfile.source());
  }

  /** Prevents loss of the module metadata and perpetual-only feature configuration. */
  @Test
  public void publishesExactAnnotationsAndMetadataFeatures() {
    FakeSessionFactory factory = new FakeSessionFactory();
    Provider provider = new Provider(factory);
    PerpetualInstrument instrument = new PerpetualInstrument("BTC", 3);
    factory.sink.onKnownInstruments(Collections.singletonList(instrument));

    assertEquals(
        Layer1ApiVersionValue.VERSION1,
        Provider.class.getAnnotation(Layer1ApiVersion.class).value());
    assertEquals("HYP", Provider.class.getAnnotation(Layer0LiveModule.class).shortName());
    assertEquals("Hyperliquid", Provider.class.getAnnotation(Layer0LiveModule.class).fullName());
    assertEquals(
        HyperliquidFieldManager.class,
        Provider.class.getAnnotation(Layer0CredentialsFieldsManager.class).value());
    Layer1ApiProviderSupportedFeatures features = provider.getSupportedFeatures();
    SubscribeInfo subscribeInfo = new SubscribeInfo("BTC", "", "PERPETUAL");
    assertEquals(Collections.singletonList(subscribeInfo), features.knownInstruments);
    assertTrue(features.depth);
    assertFalse(features.mbo);
    assertFalse(features.trading);
    assertFalse(features.exchangeUsedForSubscription);
    assertTrue(features.typeUsedForSubscription);
    assertNull(features.historicalDataInfo);
    assertDefaultAndOnly(instrument.pips(), features.pipsFunction.apply(subscribeInfo));
    assertDefaultAndOnly(
        instrument.sizeMultiplier(), features.sizeMultiplierFunction.apply(subscribeInfo));
  }

  /** Prevents the UI-facing metadata snapshot from sharing mutable session state. */
  @Test
  public void preservesBookmapInstrumentMetadataAndDefensiveKnownSnapshot() {
    FakeSessionFactory factory = new FakeSessionFactory();
    Provider provider = new Provider(factory);
    PerpetualInstrument instrument = new PerpetualInstrument("ETH", 2);
    List<PerpetualInstrument> metadata = new ArrayList<PerpetualInstrument>();
    metadata.add(instrument);
    factory.sink.onKnownInstruments(metadata);
    metadata.clear();
    RecordingInstrumentListener listener = new RecordingInstrumentListener();
    provider.addListener(listener);

    factory.sink.onInstrumentAdded(
        instrument.symbol(), instrument, BigDecimal.valueOf(instrument.pips()));

    assertEquals(
        Collections.singletonList(new SubscribeInfo("ETH", "", "PERPETUAL")),
        provider.getSupportedFeatures().knownInstruments);
    assertEquals("ETH", listener.alias);
    assertInstrument(instrument, listener.instrument);
  }

  /** Prevents an incorrect domain-to-Bookmap lifecycle or market-data event translation. */
  @Test
  public void mapsSessionEventsAndFormatsPricesFromActiveInstrument() {
    FakeSessionFactory factory = new FakeSessionFactory();
    Provider provider = new Provider(factory);
    RecordingAdminListener admin = new RecordingAdminListener();
    RecordingInstrumentListener instruments = new RecordingInstrumentListener();
    RecordingDataListener data = new RecordingDataListener();
    provider.addListener(admin);
    provider.addListener(instruments);
    provider.addListener(data);
    PerpetualInstrument instrument = new PerpetualInstrument("SOL", 4);

    factory.sink.onLoginSuccessful();
    factory.sink.onLoginFailed(LoginFailure.NO_INTERNET_CONNECTION, "no network");
    factory.sink.onLoginFailed(LoginFailure.FATAL, "fatal");
    factory.sink.onConnectionLost(ConnectionFailure.NO_INTERNET, "lost");
    factory.sink.onConnectionLost(ConnectionFailure.UNKNOWN, "unknown");
    factory.sink.onConnectionLost(ConnectionFailure.FATAL, "fatal lost");
    factory.sink.onConnectionRestored();
    factory.sink.onInstrumentAdded(
        instrument.symbol(), instrument, BigDecimal.valueOf(instrument.pips()));
    factory.sink.onInstrumentNotFound("X", "", "PERPETUAL");
    factory.sink.onInstrumentAlreadySubscribed("X", "", "PERPETUAL");
    factory.sink.onDepth("SOL", new DepthUpdate(true, 123, 45));
    factory.sink.onTrade("SOL", 123d, 45, true);
    factory.sink.onTrade("SOL", 124d, 46, false);
    factory.sink.onSystemMessage("note", MessageKind.UNCLASSIFIED);
    factory.sink.onSystemMessage("limit", MessageKind.SUBSCRIPTION_LIMIT);

    assertEquals(1, admin.loginSuccessCount);
    assertEquals(
        Arrays.asList(LoginFailedReason.NO_INTERNET_CONNECTION, LoginFailedReason.FATAL),
        admin.loginFailures);
    assertEquals(
        Arrays.asList(
            DisconnectionReason.NO_INTERNET,
            DisconnectionReason.UNKNOWN,
            DisconnectionReason.FATAL),
        admin.connectionLosses);
    assertEquals(1, admin.connectionRestoredCount);
    assertEquals(
        Arrays.asList(SystemTextMessageType.UNCLASSIFIED, SystemTextMessageType.SUBSCRIPTION_LIMIT),
        admin.systemMessageTypes);
    assertEquals(1, instruments.addedCount);
    assertEquals(1, instruments.notFoundCount);
    assertEquals(1, instruments.alreadySubscribedCount);
    assertEquals(1, data.depthCount);
    assertEquals(123, data.depthPrice);
    assertEquals(45, data.depthSize);
    assertTrue(data.depthBid);
    assertEquals(2, data.trades.size());
    assertFalse(data.trades.get(0).isOtc);
    assertTrue(data.trades.get(0).isBidAggressor);
    assertFalse(data.trades.get(1).isOtc);
    assertFalse(data.trades.get(1).isBidAggressor);
    // Bookmap passes the real price; it is rendered with the instrument's pips scale.
    assertEquals("123.00", provider.formatPrice("SOL", 123d));
    assertEquals("123.46", provider.formatPrice("SOL", 123.456d));
    assertEquals("123", provider.formatPrice("unknown", 123d));
    assertEquals("Hyperliquid realtime", provider.getSource());
    factory.sink.onInstrumentRemoved("SOL");
    assertEquals(1, instruments.removedCount);
  }

  /** Prevents the subscribe dialog from losing tick candidates derived from the reference price. */
  @Test
  public void offersTickCandidatesDerivedFromTheReferencePrice() {
    FakeSessionFactory factory = new FakeSessionFactory();
    Provider provider = new Provider(factory);
    factory.sink.onKnownInstruments(
        Collections.singletonList(new PerpetualInstrument("HYPE", 2, new BigDecimal("87.785"))));

    DefaultAndList<Double> pips =
        provider
            .getSupportedFeatures()
            .pipsFunction
            .apply(new SubscribeInfo("HYPE", "", "PERPETUAL"));

    assertEquals(Double.valueOf(0.001d), pips.valueDefault);
    assertEquals(Arrays.asList(0.001d, 0.002d, 0.005d, 0.01d, 0.1d, 1d), pips.valueOptions);
  }

  /**
   * Bookmap's Subscribe dialog upper-cases the symbol before it reaches the provider, so an
   * instrument whose Hyperliquid name is not already upper-case must still resolve.
   */
  @Test
  public void offersTickCandidatesAfterBookmapUppercasesTheSymbol() {
    FakeSessionFactory factory = new FakeSessionFactory();
    Provider provider = new Provider(factory);
    factory.sink.onKnownInstruments(
        Collections.singletonList(new PerpetualInstrument("kPEPE", 2, new BigDecimal("87.785"))));

    DefaultAndList<Double> pips =
        provider
            .getSupportedFeatures()
            .pipsFunction
            .apply(new SubscribeInfo("KPEPE", "", "PERPETUAL"));

    assertEquals(Double.valueOf(0.001d), pips.valueDefault);
    assertEquals(Arrays.asList(0.001d, 0.002d, 0.005d, 0.01d, 0.1d, 1d), pips.valueOptions);
  }

  /** Prevents an unsupported or plain subscription from losing the chosen or default tick. */
  @Test
  public void passesTheChosenTickAndFallsBackForUnsupportedOrPlainSubscriptions() {
    FakeSessionFactory factory = new FakeSessionFactory();
    Provider provider = new Provider(factory);
    factory.sink.onKnownInstruments(
        Collections.singletonList(new PerpetualInstrument("HYPE", 2, new BigDecimal("87.785"))));

    provider.subscribe(new SubscribeInfoCrypto("HYPE", "", "PERPETUAL", 0.01d, 100d));
    assertEquals(0, new BigDecimal("0.01").compareTo(factory.session.lastTick));

    provider.subscribe(new SubscribeInfoCrypto("HYPE", "", "PERPETUAL", 0.00005d, 100d));
    assertEquals(0, new BigDecimal("0.001").compareTo(factory.session.lastTick));

    provider.subscribe(new SubscribeInfo("HYPE", "", "PERPETUAL"));
    assertEquals(0, new BigDecimal("0.001").compareTo(factory.session.lastTick));

    provider.subscribe(new SubscribeInfoCrypto("UNKNOWN", "", "PERPETUAL", 0.01d, 100d));
    assertNull(factory.session.lastTick);
  }

  /** Every default-tick fallback warns; a supported Crypto tick does not. */
  @Test
  public void warnsForPlainAndInvalidTickFallbacksOnly() {
    FakeSessionFactory factory = new FakeSessionFactory();
    Provider provider = new Provider(factory);
    factory.sink.onKnownInstruments(
        Collections.singletonList(new PerpetualInstrument("HYPE", 2, new BigDecimal("87.785"))));
    List<String> warnings = new ArrayList<String>();
    Log.LogListener previous = Log.getListener();
    Log.LogLevel previousLevel = Log.getLogLevel();
    try {
      Log.setLogLevel(Log.LogLevel.WARN);
      Log.setListener(
          (level, category, message, failure) -> {
            if (level == Log.LogLevel.WARN) {
              warnings.add(message);
            }
          });
      provider.subscribe(new SubscribeInfoCrypto("HYPE", "", "PERPETUAL", 0.01d, 100d));
      assertTrue(warnings.isEmpty());
      assertEquals(new BigDecimal("0.01"), factory.session.lastTick);
      provider.subscribe(new SubscribeInfo("HYPE", "", "PERPETUAL"));
      assertEquals(1, warnings.size());
      assertTrue(warnings.get(0).contains("HYPE"));
      assertTrue(warnings.get(0).contains("0.001"));
      assertEquals(new BigDecimal("0.001"), factory.session.lastTick);
      double[] invalidTicks = {0d, -1d, Double.NaN, Double.POSITIVE_INFINITY, 0.00005d, 1e10d};
      for (double tick : invalidTicks) {
        warnings.clear();
        provider.subscribe(new SubscribeInfoCrypto("HYPE", "", "PERPETUAL", tick, 100d));
        assertEquals(1, warnings.size());
        assertEquals(new BigDecimal("0.001"), factory.session.lastTick);
      }
    } finally {
      Log.setListener(previous);
      Log.setLogLevel(previousLevel);
    }
  }

  /** Prevents the announced instrument and its price formatting from ignoring the chosen tick. */
  @Test
  public void instrumentInfoAndPriceFormattingUseTheChosenTick() {
    FakeSessionFactory factory = new FakeSessionFactory();
    Provider provider = new Provider(factory);
    RecordingInstrumentListener instruments = new RecordingInstrumentListener();
    provider.addListener(instruments);

    factory.sink.onInstrumentAdded(
        "HYPE", new PerpetualInstrument("HYPE", 2), new BigDecimal("0.01"));

    assertEquals(0.01d, instruments.instrument.pips, 0d);
    assertEquals("87.78", provider.formatPrice("HYPE", 87.78d));
  }

  /**
   * Bookmap upper-cases the symbol it asks for, so the instrument is announced under the exchange's
   * own name and the requested one travels in requestedSymbol, the field the platform reads when a
   * provider subscribes to a different symbol than it was asked for.
   */
  @Test
  public void announcesTheExchangeNameAndCarriesTheRequestedSymbol() {
    FakeSessionFactory factory = new FakeSessionFactory();
    Provider provider = new Provider(factory);
    RecordingInstrumentListener listener = new RecordingInstrumentListener();
    provider.addListener(listener);

    factory.sink.onInstrumentAdded(
        "KPEPE", new PerpetualInstrument("kPEPE", 2), new BigDecimal("0.01"));

    assertEquals("kPEPE", listener.alias);
    assertEquals("kPEPE", listener.instrument.symbol);
    assertEquals("KPEPE", listener.instrument.requestedSymbol);
  }

  /** Prevents accidental order routing through a market-data-only adapter. */
  @Test
  public void orderEntryFailsClosedWithoutTouchingSession() {
    FakeSessionFactory factory = new FakeSessionFactory();
    Provider provider = new Provider(factory);
    RecordingAdminListener admin = new RecordingAdminListener();
    provider.addListener(admin);

    provider.sendOrder(new EmptyOrderSendParameters());
    provider.updateOrder(new OrderUpdateParameters("order-id"));

    assertEquals(
        Arrays.asList(SystemTextMessageType.ORDER_FAILURE, SystemTextMessageType.ORDER_FAILURE),
        admin.systemMessageTypes);
    assertEquals(
        Arrays.asList(
            "Hyperliquid adapter is read-only; order operations are not supported.",
            "Hyperliquid adapter is read-only; order operations are not supported."),
        admin.systemMessages);
    assertEquals(0, factory.session.commandCount);
  }

  private static CredentialsSerializationField field(String value) {
    return new CredentialsSerializationField(true, false, value);
  }

  private static final class PlainLoginData implements LoginData {
    private static final long serialVersionUID = 1L;
  }

  private static final class EmptyOrderSendParameters implements OrderSendParameters {
    @Override
    public String toString() {
      return "empty";
    }
  }

  private static void assertDefaultAndOnly(double expected, DefaultAndList<Double> values) {
    assertEquals(expected, values.valueDefault.doubleValue(), 0.0d);
    assertEquals(Collections.singletonList(Double.valueOf(expected)), values.valueOptions);
  }

  private static void assertInstrument(PerpetualInstrument expected, InstrumentInfo actual) {
    assertEquals(expected.symbol(), actual.symbol);
    assertEquals("", actual.exchange);
    assertEquals("PERPETUAL", actual.type);
    assertEquals(expected.pips(), actual.pips, 0.0d);
    assertEquals(1d, actual.multiplier, 0.0d);
    assertNull(actual.fullName);
    assertFalse(actual.isFullDepth);
    assertEquals(expected.sizeMultiplier(), actual.sizeMultiplier, 0.0d);
    assertTrue(actual.isCrypto);
  }

  private static final class FakeSessionFactory implements HyperliquidSessionFactory {
    private final FakeSession session = new FakeSession();
    private SessionSink sink;

    @Override
    public HyperliquidSessionApi create(SessionSink createdSink) {
      sink = createdSink;
      return session;
    }
  }

  private static final class FakeSession implements HyperliquidSessionApi {
    private SourceProfile lastLoginProfile;
    private int commandCount;
    private BigDecimal lastTick;

    @Override
    public void login(SourceProfile profile) {
      commandCount++;
      lastLoginProfile = profile;
    }

    @Override
    public void subscribe(String symbol, String exchange, String type) {
      commandCount++;
    }

    @Override
    public void subscribe(String symbol, String exchange, String type, BigDecimal tick) {
      commandCount++;
      lastTick = tick;
    }

    @Override
    public void unsubscribe(String alias) {
      commandCount++;
    }

    @Override
    public void close() {
      commandCount++;
    }
  }

  private static final class RecordingAdminListener implements Layer1ApiAdminAdapter {
    private int loginSuccessCount;
    private int connectionRestoredCount;
    private final List<LoginFailedReason> loginFailures = new ArrayList<LoginFailedReason>();
    private final List<DisconnectionReason> connectionLosses = new ArrayList<DisconnectionReason>();
    private final List<SystemTextMessageType> systemMessageTypes =
        new ArrayList<SystemTextMessageType>();
    private final List<String> systemMessages = new ArrayList<String>();

    @Override
    public void onLoginSuccessful() {
      loginSuccessCount++;
    }

    @Override
    public void onLoginFailed(LoginFailedReason reason, String message) {
      loginFailures.add(reason);
    }

    @Override
    public void onConnectionLost(DisconnectionReason reason, String message) {
      connectionLosses.add(reason);
    }

    @Override
    public void onConnectionRestored() {
      connectionRestoredCount++;
    }

    @Override
    public void onSystemTextMessage(String message, SystemTextMessageType type) {
      systemMessages.add(message);
      systemMessageTypes.add(type);
    }
  }

  private static final class RecordingInstrumentListener implements Layer1ApiInstrumentAdapter {
    private String alias;
    private InstrumentInfo instrument;
    private int addedCount;
    private int removedCount;
    private int notFoundCount;
    private int alreadySubscribedCount;

    @Override
    public void onInstrumentAdded(String addedAlias, InstrumentInfo addedInstrument) {
      alias = addedAlias;
      instrument = addedInstrument;
      addedCount++;
    }

    @Override
    public void onInstrumentRemoved(String removedAlias) {
      removedCount++;
    }

    @Override
    public void onInstrumentNotFound(String symbol, String exchange, String type) {
      notFoundCount++;
    }

    @Override
    public void onInstrumentAlreadySubscribed(String symbol, String exchange, String type) {
      alreadySubscribedCount++;
    }
  }

  private static final class RecordingDataListener implements Layer1ApiDataAdapter {
    private int depthCount;
    private boolean depthBid;
    private int depthPrice;
    private int depthSize;
    private final List<TradeInfo> trades = new ArrayList<TradeInfo>();

    @Override
    public void onDepth(String alias, boolean bid, int price, int size) {
      depthCount++;
      depthBid = bid;
      depthPrice = price;
      depthSize = size;
    }

    @Override
    public void onTrade(String alias, double price, int size, TradeInfo trade) {
      trades.add(trade);
    }
  }
}
