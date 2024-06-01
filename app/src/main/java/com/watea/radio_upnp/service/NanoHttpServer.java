package com.watea.radio_upnp.service;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.watea.radio_upnp.R;
import com.watea.radio_upnp.model.Radio;

import java.io.IOException;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import fi.iki.elonen.NanoHTTPD;

public class NanoHttpServer extends NanoHTTPD {
  private static final String LOG_TAG = NanoHttpServer.class.getName();
  private final Context context;
  private final Set<Handler> handlers = new HashSet<>();
  private final RadioHandler radioHandler;
  private final ResourceHandler resourceHandler;

  public NanoHttpServer(@NonNull Context context) {
    super(0);
    this.context = context;
    radioHandler = new RadioHandler(this.context.getString(R.string.app_name));
    // RadioHandler
    handlers.add(radioHandler);
    // ResourceHandler
    final Uri uri = getUri();
    if (uri == null) {
      Log.d(LOG_TAG, "NanoHttpServer fails to create Uri");
      resourceHandler = null;
    } else {
      resourceHandler = new ResourceHandler(uri);
      //handlers.add(new ResourceHandler(uri));
    }
  }

  // First non null response is taken
  @Override
  public Response serve(IHTTPSession session) {
    return handlers.stream()
      .map(handler -> handler.handle(session))
      .filter(Objects::nonNull)
      .findAny()
      .orElse(null);
  }

  public void start() throws IOException {
    start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
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
    return resourceHandler.createLogoFile(radio);
  }

  public interface Handler {
    NanoHTTPD.Response handle(@NonNull NanoHTTPD.IHTTPSession iHTTPSession);
  }
}