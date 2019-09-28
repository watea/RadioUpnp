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
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import com.watea.radio_upnp.model.Radio;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.server.handler.ResourceHandler;

import java.io.FileOutputStream;

@SuppressWarnings("WeakerAccess")
public class HttpServer extends Thread {
  private static final String LOG_TAG = HttpServer.class.getName();
  private static final String LOGO_FILE = "logo";
  private static final int PORT = 57648;
  @NonNull
  private final Context context;
  @NonNull
  private final Server server;
  @NonNull
  private final RadioHandler radioHandler;
  @NonNull
  private final Listener listener;

  public HttpServer(
    @NonNull Context context,
    @NonNull RadioHandler radioHandler,
    @NonNull Listener listener) {
    this.context = context;
    server = new Server(PORT);
    this.listener = listener;
    this.radioHandler = radioHandler;
    // Handler for local files
    ResourceHandler resourceHandler = new ResourceHandler();
    resourceHandler.setResourceBase(this.context.getFilesDir().getPath());
    // Add the ResourceHandler to the server
    HandlerList handlers = new HandlerList();
    handlers.setHandlers(new Handler[]{resourceHandler, this.radioHandler});
    server.setHandler(handlers);
  }

  @NonNull
  public static Uri getLoopbackUri() {
    return NetworkTester.getLoopbackUri(PORT);
  }

  public void setRadioHandlerListener(@Nullable RadioHandler.Listener listener) {
    radioHandler.setListener(listener);
  }

  @Override
  public void run() {
    super.run();
    try {
      Log.d(LOG_TAG, "HTTP server start");
      server.start();
      server.join();
    } catch (Exception exception) {
      Log.d(LOG_TAG, "HTTP server start error");
      listener.onError();
    }
  }

  public void stopServer() {
    try {
      Log.d(LOG_TAG, "HTTP server stop");
      server.stop();
    } catch (Exception exception) {
      Log.d(LOG_TAG, "HTTP server stop error");
    }
  }

  // Return logo file Uri; a jpeg file
  @Nullable
  public Uri createLogoFile(@NonNull Radio radio, int size) {
    String name = LOGO_FILE + ".jpg";
    try (FileOutputStream fileOutputStream = context.openFileOutput(name, Context.MODE_PRIVATE)) {
      Bitmap
        .createScaledBitmap(radio.getIcon(), size, size, false)
        .compress(Bitmap.CompressFormat.JPEG, 100, fileOutputStream);
    } catch (Exception exception) {
      Log.e(LOG_TAG, "createLogoFile: internal failure creating logo file");
    }
    Uri uri = getUri();
    return (uri == null) ? null : uri.buildUpon().appendEncodedPath(name).build();
  }

  @Nullable
  public Uri getUri() {
    return NetworkTester.getUri(context, PORT);
  }

  public interface Listener {
    void onError();
  }
}