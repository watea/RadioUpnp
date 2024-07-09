package com.watea.radio_upnp.service;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.watea.radio_upnp.R;
import com.watea.radio_upnp.model.Radio;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import fi.iki.elonen.NanoHTTPD;

public class NanoHttpServer extends NanoHTTPD {
  private final Context context;
  private final Set<Handler> handlers = new HashSet<>();
  @NonNull
  private final RadioHandler radioHandler;
  @NonNull
  private final ResourceHandler resourceHandler = new ResourceHandler();

  public NanoHttpServer(
    @NonNull Context context,
    @NonNull RadioHandler.Listener radioHandlerListener) throws IOException {
    super(findFreePort());
    this.context = context;
    radioHandler =
      new RadioHandler(this.context.getString(R.string.app_name), radioHandlerListener);
    handlers.add(radioHandler);
    handlers.add(resourceHandler);
  }

  private static int findFreePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
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
    final Uri uri = getUri();
    assert uri != null;
    return uri.buildUpon().appendEncodedPath(resourceHandler.createLogoFile(radio)).build();
  }

  public interface Handler {
    NanoHTTPD.Response handle(@NonNull NanoHTTPD.IHTTPSession iHTTPSession);
  }
}