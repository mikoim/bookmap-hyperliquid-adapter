package com.bookmap.plugins.layer0.hyperliquid;

import com.bookmap.plugins.layer0.hyperliquid.budget.HyperliquidProcessBudget;
import com.bookmap.plugins.layer0.hyperliquid.concurrent.ExecutorScheduler;
import com.bookmap.plugins.layer0.hyperliquid.concurrent.StateEventDispatcher;
import com.bookmap.plugins.layer0.hyperliquid.model.DepthUpdate;
import com.bookmap.plugins.layer0.hyperliquid.model.PerpetualInstrument;
import com.bookmap.plugins.layer0.hyperliquid.parse.HyperliquidMessageParser;
import com.bookmap.plugins.layer0.hyperliquid.parse.HyperliquidMetaParser;
import com.bookmap.plugins.layer0.hyperliquid.session.ConnectionFailure;
import com.bookmap.plugins.layer0.hyperliquid.session.HyperliquidSession;
import com.bookmap.plugins.layer0.hyperliquid.session.HyperliquidSessionApi;
import com.bookmap.plugins.layer0.hyperliquid.session.LoginFailure;
import com.bookmap.plugins.layer0.hyperliquid.session.MessageKind;
import com.bookmap.plugins.layer0.hyperliquid.session.SessionSink;
import com.bookmap.plugins.layer0.hyperliquid.transport.HyperliquidTransport;
import com.bookmap.plugins.layer0.hyperliquid.transport.JettyHyperliquidTransport;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import velox.api.layer0.annotations.Layer0CredentialsFieldsManager;
import velox.api.layer0.annotations.Layer0LiveModule;
import velox.api.layer0.live.ExternalLiveBaseProvider;
import velox.api.layer1.Layer1ApiAdminListener;
import velox.api.layer1.Layer1ApiDataListener;
import velox.api.layer1.Layer1ApiInstrumentListener;
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
import velox.api.layer1.data.SystemTextMessageType;
import velox.api.layer1.data.TradeInfo;

/** Bookmap Layer 0 provider for Hyperliquid perpetual market data. */
@Layer1ApiVersion(Layer1ApiVersionValue.VERSION1)
@Layer0LiveModule(shortName = "HYP", fullName = "Hyperliquid")
@Layer0CredentialsFieldsManager(HyperliquidFieldManager.class)
public final class Provider extends ExternalLiveBaseProvider {

  private static final double FALLBACK_PIPS = 1.0d;
  private static final String SOURCE = "Hyperliquid realtime";
  private static final String ORDER_FAILURE_MESSAGE =
      "Hyperliquid adapter is read-only; order operations are not supported.";

  private final HyperliquidSessionApi session;
  private volatile Map<String, PerpetualInstrument> knownBySymbol = Collections.emptyMap();
  private volatile List<SubscribeInfo> knownSubscribeInfo = Collections.emptyList();
  private volatile Map<String, InstrumentInfo> activeInstruments = Collections.emptyMap();

  /** Creates a provider with the production transport and asynchronous execution boundaries. */
  public Provider() {
    this(new ProductionSessionFactory());
  }

  /** Creates a provider with a package-private session factory for focused tests. */
  Provider(HyperliquidSessionFactory factory) {
    if (factory == null) {
      throw new IllegalArgumentException("session factory must not be null");
    }
    this.session = factory.create(new ProviderSessionSink());
    if (session == null) {
      throw new IllegalArgumentException("session factory returned null");
    }
  }

  @Override
  public void login(LoginData loginData) {
    session.login(SourceProfile.of(MarketDataSource.HYPERLIQUID, environment(loginData)));
  }

  @Override
  public void subscribe(SubscribeInfo subscribeInfo) {
    if (subscribeInfo != null) {
      session.subscribe(subscribeInfo.symbol, subscribeInfo.exchange, subscribeInfo.type);
    }
  }

  @Override
  public void unsubscribe(String alias) {
    session.unsubscribe(alias);
  }

  @Override
  public String formatPrice(String alias, double price) {
    InstrumentInfo instrument = activeInstruments.get(alias);
    double pips = instrument == null ? FALLBACK_PIPS : instrument.pips;
    try {
      return formatPriceDefault(price, pips);
    } catch (NoClassDefFoundError missingBookmapFormatter) {
      // api-core 7.4.0.10's test artifact omits the formatter it references.
      return BigDecimal.valueOf(price)
          .multiply(BigDecimal.valueOf(pips))
          .stripTrailingZeros()
          .toPlainString();
    }
  }

  @Override
  public void sendOrder(OrderSendParameters orderSendParameters) {
    sendOrderFailure();
  }

  @Override
  public void updateOrder(OrderUpdateParameters orderUpdateParameters) {
    sendOrderFailure();
  }

  @Override
  public String getSource() {
    return SOURCE;
  }

  @Override
  public void close() {
    session.close();
  }

  @Override
  public Layer1ApiProviderSupportedFeatures getSupportedFeatures() {
    return super.getSupportedFeatures().toBuilder()
        .setDepth(true)
        .setMbo(false)
        .setTrading(false)
        .setExchangeUsedForSubscription(false)
        .setTypeUsedForSubscription(true)
        .setKnownInstruments(knownSubscribeInfo)
        .setPipsFunction(this::pipsFor)
        .setSizeMultiplierFunction(this::sizeMultiplierFor)
        .build();
  }

  private DefaultAndList<Double> pipsFor(SubscribeInfo subscribeInfo) {
    PerpetualInstrument instrument = instrumentFor(subscribeInfo);
    double pips = instrument == null ? FALLBACK_PIPS : instrument.pips();
    return new DefaultAndList<Double>(Double.valueOf(pips), Collections.singletonList(pips));
  }

  private DefaultAndList<Double> sizeMultiplierFor(SubscribeInfo subscribeInfo) {
    PerpetualInstrument instrument = instrumentFor(subscribeInfo);
    double multiplier = instrument == null ? FALLBACK_PIPS : instrument.sizeMultiplier();
    return new DefaultAndList<Double>(
        Double.valueOf(multiplier), Collections.singletonList(multiplier));
  }

  private PerpetualInstrument instrumentFor(SubscribeInfo subscribeInfo) {
    return subscribeInfo == null ? null : knownBySymbol.get(subscribeInfo.symbol);
  }

  private void sendOrderFailure() {
    for (Layer1ApiAdminListener listener : adminListeners) {
      listener.onSystemTextMessage(ORDER_FAILURE_MESSAGE, SystemTextMessageType.ORDER_FAILURE);
    }
  }

  private static HyperliquidEnvironment environment(LoginData loginData) {
    if (!(loginData instanceof ExtendedLoginData)) {
      return HyperliquidEnvironment.MAINNET;
    }
    ExtendedLoginData extendedLoginData = (ExtendedLoginData) loginData;
    if (extendedLoginData.extendedData == null) {
      return HyperliquidEnvironment.MAINNET;
    }
    velox.api.layer0.credentialscomponents.CredentialsSerializationField field =
        extendedLoginData.extendedData.get(HyperliquidFieldManager.TESTNET_FIELD);
    return field != null && Boolean.parseBoolean(field.getStringValue())
        ? HyperliquidEnvironment.TESTNET
        : HyperliquidEnvironment.MAINNET;
  }

  private final class ProviderSessionSink implements SessionSink {

    @Override
    public void onKnownInstruments(List<PerpetualInstrument> instruments) {
      Map<String, PerpetualInstrument> bySymbol = new HashMap<String, PerpetualInstrument>();
      List<SubscribeInfo> subscribeInfo = new ArrayList<SubscribeInfo>();
      if (instruments != null) {
        for (PerpetualInstrument instrument : instruments) {
          if (instrument != null) {
            bySymbol.put(instrument.symbol(), instrument);
            subscribeInfo.add(new SubscribeInfo(instrument.symbol(), "", "PERPETUAL"));
          }
        }
      }
      knownBySymbol = Collections.unmodifiableMap(bySymbol);
      knownSubscribeInfo = Collections.unmodifiableList(subscribeInfo);
    }

    @Override
    public void onInstrumentAdded(PerpetualInstrument instrument) {
      if (instrument == null) {
        return;
      }
      InstrumentInfo info =
          new InstrumentInfo(
              instrument.symbol(),
              "",
              "PERPETUAL",
              instrument.pips(),
              1d,
              null,
              false,
              instrument.sizeMultiplier(),
              true);
      Map<String, InstrumentInfo> updated = new HashMap<String, InstrumentInfo>(activeInstruments);
      updated.put(instrument.symbol(), info);
      activeInstruments = Collections.unmodifiableMap(updated);
      for (Layer1ApiInstrumentListener listener : instrumentListeners) {
        listener.onInstrumentAdded(instrument.symbol(), info);
      }
    }

    @Override
    public void onInstrumentRemoved(String alias) {
      Map<String, InstrumentInfo> updated = new HashMap<String, InstrumentInfo>(activeInstruments);
      updated.remove(alias);
      activeInstruments = Collections.unmodifiableMap(updated);
      for (Layer1ApiInstrumentListener listener : instrumentListeners) {
        listener.onInstrumentRemoved(alias);
      }
    }

    @Override
    public void onInstrumentNotFound(String symbol, String exchange, String type) {
      for (Layer1ApiInstrumentListener listener : instrumentListeners) {
        listener.onInstrumentNotFound(symbol, exchange, type);
      }
    }

    @Override
    public void onInstrumentAlreadySubscribed(String symbol, String exchange, String type) {
      for (Layer1ApiInstrumentListener listener : instrumentListeners) {
        listener.onInstrumentAlreadySubscribed(symbol, exchange, type);
      }
    }

    @Override
    public void onDepth(String alias, DepthUpdate update) {
      if (update == null) {
        return;
      }
      for (Layer1ApiDataListener listener : dataListeners) {
        listener.onDepth(alias, update.bid(), update.price(), update.size());
      }
    }

    @Override
    public void onTrade(String alias, double priceUnits, int sizeUnits, boolean isBuyAggressor) {
      TradeInfo tradeInfo = new TradeInfo(false, isBuyAggressor);
      for (Layer1ApiDataListener listener : dataListeners) {
        listener.onTrade(alias, priceUnits, sizeUnits, tradeInfo);
      }
    }

    @Override
    public void onSystemMessage(String message, MessageKind kind) {
      SystemTextMessageType type =
          kind == null
              ? SystemTextMessageType.UNCLASSIFIED
              : SystemTextMessageType.valueOf(kind.name());
      for (Layer1ApiAdminListener listener : adminListeners) {
        listener.onSystemTextMessage(message, type);
      }
    }

    @Override
    public void onDiagnostic(String message) {
      Log.warn(message);
    }

    @Override
    public void onLoginSuccessful() {
      for (Layer1ApiAdminListener listener : adminListeners) {
        listener.onLoginSuccessful();
      }
    }

    @Override
    public void onLoginFailed(LoginFailure failure, String message) {
      LoginFailedReason reason =
          failure == null ? LoginFailedReason.UNKNOWN : LoginFailedReason.valueOf(failure.name());
      for (Layer1ApiAdminListener listener : adminListeners) {
        listener.onLoginFailed(reason, message);
      }
    }

    @Override
    public void onConnectionLost(ConnectionFailure failure, String message) {
      DisconnectionReason reason =
          failure == null
              ? DisconnectionReason.UNKNOWN
              : DisconnectionReason.valueOf(failure.name());
      for (Layer1ApiAdminListener listener : adminListeners) {
        listener.onConnectionLost(reason, message);
      }
    }

    @Override
    public void onConnectionRestored() {
      for (Layer1ApiAdminListener listener : adminListeners) {
        listener.onConnectionRestored();
      }
    }
  }

  private static final class ProductionSessionFactory implements HyperliquidSessionFactory {

    @Override
    public HyperliquidSessionApi create(SessionSink sink) {
      ExecutorService stateExecutor =
          Executors.newSingleThreadExecutor(
              runnable -> {
                Thread thread = new Thread(runnable, "hyperliquid-state");
                thread.setDaemon(true);
                return thread;
              });
      ScheduledExecutorService timerExecutor =
          Executors.newSingleThreadScheduledExecutor(
              runnable -> {
                Thread thread = new Thread(runnable, "hyperliquid-timer");
                thread.setDaemon(true);
                return thread;
              });
      ExecutorScheduler scheduler = new ExecutorScheduler(timerExecutor);
      LongSupplier clock = System::currentTimeMillis;
      HyperliquidProcessBudget budget = HyperliquidProcessBudget.shared();
      HyperliquidTransport transport = new JettyHyperliquidTransport();
      AtomicReference<HyperliquidSession> sessionRef = new AtomicReference<HyperliquidSession>();
      StateEventDispatcher dispatcher =
          new StateEventDispatcher(stateExecutor, 4096, () -> sessionRef.get().onMarketOverflow());
      HyperliquidConnector connector =
          new HyperliquidConnector(
              transport,
              new HyperliquidMetaParser(),
              budget,
              scheduler,
              clock,
              dispatcher::submitControl);
      HyperliquidSession session =
          new HyperliquidSession(
              connector,
              new HyperliquidMessageParser(),
              budget,
              scheduler,
              clock,
              dispatcher,
              sink,
              () -> {
                scheduler.close();
                stateExecutor.shutdown();
              });
      sessionRef.set(session);
      return session;
    }
  }
}
