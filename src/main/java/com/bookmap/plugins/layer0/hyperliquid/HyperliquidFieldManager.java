package com.bookmap.plugins.layer0.hyperliquid;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import velox.api.layer0.credentialscomponents.CredentialsCheckbox;
import velox.api.layer0.credentialscomponents.CredentialsComponent;
import velox.api.layer0.credentialscomponents.CredentialsFieldManager;
import velox.api.layer0.credentialscomponents.CredentialsSerializationField;

/** Supplies the optional Hyperliquid environment selector. */
public final class HyperliquidFieldManager implements CredentialsFieldManager {

  public static final String TESTNET_FIELD = "testnet";

  private final CredentialsCheckbox testnet =
      new CredentialsCheckbox(TESTNET_FIELD, true, "Use Hyperliquid testnet");

  @Override
  public List<CredentialsComponent> getCredentialsComponents() {
    return Collections.<CredentialsComponent>singletonList(testnet);
  }

  @Override
  public boolean isConfigured(Map<String, CredentialsSerializationField> fields) {
    return true;
  }
}
