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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import velox.api.layer0.annotations.Layer0CredentialsFieldsManager;
import velox.api.layer0.annotations.Layer0LiveModule;
import velox.api.layer0.credentialscomponents.CredentialsSerializationField;
import velox.api.layer1.Layer1ApiAdminAdapter;
import velox.api.layer1.Layer1ApiDataAdapter;
import velox.api.layer1.Layer1ApiInstrumentAdapter;
import velox.api.layer1.annotations.Layer1ApiVersion;
import velox.api.layer1.annotations.Layer1ApiVersionValue;
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

    factory.sink.onInstrumentAdded(instrument);

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
    factory.sink.onInstrumentAdded(instrument);
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
    assertEquals("1.23", provider.formatPrice("SOL", 123d));
    assertEquals("123", provider.formatPrice("unknown", 123d));
    assertEquals("Hyperliquid realtime", provider.getSource());
    factory.sink.onInstrumentRemoved("SOL");
    assertEquals(1, instruments.removedCount);
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
