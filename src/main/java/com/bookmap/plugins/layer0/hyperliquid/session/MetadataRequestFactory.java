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
