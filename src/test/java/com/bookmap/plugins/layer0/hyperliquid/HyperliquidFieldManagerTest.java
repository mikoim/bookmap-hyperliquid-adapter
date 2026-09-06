package com.bookmap.plugins.layer0.hyperliquid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import org.junit.Test;
import velox.api.layer0.credentialscomponents.CredentialsCheckbox;
import velox.api.layer0.credentialscomponents.CredentialsComponent;
import velox.api.layer0.credentialscomponents.CredentialsDropdown;

/** Tests the sole non-secret login field exposed by the adapter. */
public class HyperliquidFieldManagerTest {

  /** The source dropdown comes first (Hyperliquid default) and the testnet checkbox stays. */
  @Test
  public void exposesSourceDropdownAndOptionalTestnetCheckbox() {
    HyperliquidFieldManager manager = new HyperliquidFieldManager();

    assertEquals(2, manager.getCredentialsComponents().size());
    CredentialsComponent first = manager.getCredentialsComponents().get(0);
    assertTrue(first instanceof CredentialsDropdown);
    CredentialsDropdown dropdown = (CredentialsDropdown) first;
    assertEquals(HyperliquidFieldManager.SOURCE_FIELD, dropdown.getName());
    assertTrue(dropdown.isKey());
    CredentialsComponent second = manager.getCredentialsComponents().get(1);
    assertTrue(second instanceof CredentialsCheckbox);
    CredentialsCheckbox checkbox = (CredentialsCheckbox) second;
    assertEquals(HyperliquidFieldManager.TESTNET_FIELD, checkbox.getName());
    assertTrue(checkbox.isKey());
    assertFalse(checkbox.getValue());
    assertTrue(manager.isConfigured(Collections.emptyMap()));
  }
}
