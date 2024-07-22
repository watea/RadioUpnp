package com.watea.radio_upnp.service;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.watea.radio_upnp.R;
import com.watea.radio_upnp.model.Radio;

import java.io.IOException;

public class RadioHttpServer extends HttpServer {
  @NonNull
  private final RadioHandler radioHandler;
  @NonNull
  private final ResourceHandler resourceHandler = new ResourceHandler();
  @NonNull
  private final Context context;

  public RadioHttpServer(
    @NonNull Context context,
    @NonNull RadioHandler.Listener radioHandlerListener) throws IOException {
    this.context = context;
    radioHandler =
      new RadioHandler(this.context.getString(R.string.app_name), radioHandlerListener);
    addHandler(radioHandler);
    addHandler(resourceHandler);
  }

  @Nullable
  public Uri getUri() {
    return new NetworkProxy(context).getUri(getListeningPort());
  }

  @NonNull
  public Uri getLoopbackUri() {
    return NetworkProxy.getLoopbackUri(getListeningPort());
  }

  public void resetRadioHandlerController() {
    radioHandler.resetController();
  }

  public void setRadioHandlerController(@NonNull RadioHandler.Controller radioHandlerController) {
    radioHandler.setController(radioHandlerController);
  }

  @Nullable
  public Uri createLogoFile(@NonNull Radio radio) {
    final Uri uri = getUri();
    assert uri != null;
    return uri.buildUpon().appendEncodedPath(resourceHandler.createLogoFile(radio)).build();
  }
}