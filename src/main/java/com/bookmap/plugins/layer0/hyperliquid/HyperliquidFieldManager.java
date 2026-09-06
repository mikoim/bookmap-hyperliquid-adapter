package com.bookmap.plugins.layer0.hyperliquid;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import velox.api.layer0.credentialscomponents.CredentialsCheckbox;
import velox.api.layer0.credentialscomponents.CredentialsComponent;
import velox.api.layer0.credentialscomponents.CredentialsDropdown;
import velox.api.layer0.credentialscomponents.CredentialsFieldManager;
import velox.api.layer0.credentialscomponents.CredentialsSerializationField;

/** Supplies the order-book source dropdown and the optional Hyperliquid environment selector. */
public final class HyperliquidFieldManager implements CredentialsFieldManager {

  public static final String SOURCE_FIELD = "source";
  public static final String TESTNET_FIELD = "testnet";

  private final CredentialsDropdown source =
      new CredentialsDropdown(
          SOURCE_FIELD, true, "Order book source", MarketDataSource.fieldValues());
  private final CredentialsCheckbox testnet =
      new CredentialsCheckbox(TESTNET_FIELD, true, "Use Hyperliquid testnet");

  @Override
  public List<CredentialsComponent> getCredentialsComponents() {
    return Arrays.<CredentialsComponent>asList(source, testnet);
  }

  @Override
  public boolean isConfigured(Map<String, CredentialsSerializationField> fields) {
    return true;
  }
}
