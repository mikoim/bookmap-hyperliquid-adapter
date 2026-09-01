package com.bookmap.plugins.layer0.hyperliquid;

import com.bookmap.plugins.layer0.hyperliquid.session.HyperliquidSessionApi;
import com.bookmap.plugins.layer0.hyperliquid.session.SessionSink;

/** Package-private construction seam used to keep the Bookmap adapter testable. */
interface HyperliquidSessionFactory {

  HyperliquidSessionApi create(SessionSink sink);
}
