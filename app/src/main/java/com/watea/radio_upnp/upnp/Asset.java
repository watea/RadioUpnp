package com.watea.radio_upnp.upnp;

import androidx.annotation.NonNull;

public interface Asset {
  boolean isComplete();

  @NonNull
  URLService.Consumer getXMLBuilder();
}