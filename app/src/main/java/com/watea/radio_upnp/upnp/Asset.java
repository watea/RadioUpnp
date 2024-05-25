package com.watea.radio_upnp.upnp;

import androidx.annotation.NonNull;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.net.URL;

public abstract class Asset implements URLService.Consumer {
  protected final Callback callback;

  // Callback to call when job is done
  protected Asset(@NonNull Callback callback) {
    this.callback = callback;
  }

  // Default is no callback
  protected Asset() {
    this(asset -> {});
  }

  @Override
  public void endParseAccept(@NonNull URLService uRLService) {
  }

  protected abstract boolean isComplete();

  protected void hydrate
    (@NonNull URLService uRLService) throws IOException, XmlPullParserException {
    uRLService.fetchContent().parseXml(this);
  }

  public interface Callback {
    void onComplete(@NonNull Asset asset);
  }
}