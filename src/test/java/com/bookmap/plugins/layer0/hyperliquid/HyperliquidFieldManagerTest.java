package com.bookmap.plugins.layer0.hyperliquid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import org.junit.Test;
import velox.api.layer0.credentialscomponents.CredentialsCheckbox;
import velox.api.layer0.credentialscomponents.CredentialsComponent;

/** Tests the sole non-secret login field exposed by the adapter. */
public class HyperliquidFieldManagerTest {

  /** Prevents adding credential fields or making testnet the default environment. */
  @Test
  public void exposesOnlyAnOptionalTestnetCheckbox() {
    HyperliquidFieldManager manager = new HyperliquidFieldManager();

    assertEquals(1, manager.getCredentialsComponents().size());
    CredentialsComponent component = manager.getCredentialsComponents().get(0);
    assertTrue(component instanceof CredentialsCheckbox);
    CredentialsCheckbox checkbox = (CredentialsCheckbox) component;
    assertEquals(HyperliquidFieldManager.TESTNET_FIELD, checkbox.getName());
    assertTrue(checkbox.isKey());
    assertFalse(checkbox.getValue());
    assertTrue(manager.isConfigured(Collections.emptyMap()));
  }
}
