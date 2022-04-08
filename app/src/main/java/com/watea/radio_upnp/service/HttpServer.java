/*
 * Copyright (c) 2018. Stephane Treuchot
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
 * of the Software, and to permit persons to whom the Software is furnished to
 * do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

package com.watea.radio_upnp.service;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.watea.radio_upnp.model.Radio;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.server.handler.ResourceHandler;

import java.io.FileOutputStream;

public class HttpServer extends Thread {
  private static final String LOG_TAG = HttpServer.class.getName();
  private static final String LOGO_FILE = "logo";
  private static final int REMOTE_LOGO_SIZE = 300;
  private final Server server = new Server(0);
  @NonNull
  private final Context context;
  @NonNull
  private final NetworkProxy networkProxy;
  @NonNull
  private final Listener listener;
  @NonNull
  private final RadioHandler radioHandler;

  public HttpServer(
    @NonNull Context context,
    @NonNull String userAgent,
    @NonNull RadioHandler.Callback radioHandlerCallback,
    @NonNull RadioHandler.Listener radioHandlerListener,
    @NonNull Listener listener) {
    this.context = context;
    this.listener = listener;
    radioHandler = new RadioHandler(userAgent, radioHandlerCallback, radioHandlerListener);
    networkProxy = new NetworkProxy(this.context);
    // Handler for local files
    ResourceHandler resourceHandler = new ResourceHandler();
    resourceHandler.setResourceBase(this.context.getFilesDir().getPath());
    // Add the ResourceHandler to the server
    HandlerList handlers = new HandlerList();
    handlers.setHandlers(new Handler[]{resourceHandler, radioHandler});
    server.setHandler(handlers);
  }

  public void setRadioHandlerController(@Nullable RadioHandler.Controller radioHandlerController) {
    radioHandler.setController(radioHandlerController);
  }

  @NonNull
  public Uri getLoopbackUri() {
    return NetworkProxy.getLoopbackUri(getPort());
  }

  @Override
  public void run() {
    super.run();
    try {
      Log.d(LOG_TAG, "HTTP server start");
      server.start();
      server.join();
    } catch (Exception exception) {
      Log.d(LOG_TAG, "HTTP server start error", exception);
      listener.onError();
    }
  }

  public void stopServer() {
    try {
      Log.d(LOG_TAG, "HTTP server stop");
      server.stop();
    } catch (Exception exception) {
      Log.i(LOG_TAG, "HTTP server stop error", exception);
    }
  }

  // Return logo file Uri; a jpeg file
  @Nullable
  public Uri createLogoFile(@NonNull Radio radio) {
    String name = LOGO_FILE + radio.getId() + ".jpg";
    try (FileOutputStream fileOutputStream = context.openFileOutput(name, Context.MODE_PRIVATE)) {
      Bitmap
        .createScaledBitmap(radio.getIcon(), REMOTE_LOGO_SIZE, REMOTE_LOGO_SIZE, false)
        .compress(Bitmap.CompressFormat.JPEG, 100, fileOutputStream);
    } catch (Exception exception) {
      Log.e(LOG_TAG, "createLogoFile: internal failure creating logo file", exception);
    }
    Uri uri = getUri();
    return (uri == null) ? null : uri.buildUpon().appendEncodedPath(name).build();
  }

  @Nullable
  public Uri getUri() {
    return networkProxy.getUri(getPort());
  }

  private int getPort() {
    return server.getConnectors()[0].getLocalPort();
  }

  public interface Listener {
    void onError();
  }
}