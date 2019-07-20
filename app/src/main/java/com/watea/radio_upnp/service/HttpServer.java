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
import android.util.Log;

import com.watea.radio_upnp.model.Radio;
import com.watea.radio_upnp.model.RadioLibrary;

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
  private final Context mContext;
  @NonNull
  private final Server mServer;
  @NonNull
  private final RadioHandler mRadioHandler;
  @NonNull
  private final Listener mListener;

  public HttpServer(
    @NonNull Context context,
    @NonNull String userAgent,
    @NonNull RadioLibrary radioLibrary,
    @NonNull Listener listener) {
    mContext = context;
    mServer = new Server(PORT);
    mListener = listener;
    // Handler for local files
    ResourceHandler resourceHandler = new ResourceHandler();
    resourceHandler.setResourceBase(mContext.getFilesDir().getPath());
    // Handler for radio stream
    mRadioHandler = new RadioHandler(userAgent, radioLibrary, true);
    // Add the ResourceHandler to the server
    HandlerList handlers = new HandlerList();
    handlers.setHandlers(new Handler[]{resourceHandler, mRadioHandler});
    mServer.setHandler(handlers);
  }

  @NonNull
  public static Uri getLoopbackUri() {
    return NetworkTester.getLoopbackUri(PORT);
  }

  @NonNull
  public RadioHandler getRadioHandler() {
    return mRadioHandler;
  }

  @Override
  public void run() {
    super.run();
    try {
      Log.d(LOG_TAG, "HTTP server start");
      mServer.start();
      mServer.join();
    } catch (Exception exception) {
      Log.d(LOG_TAG, "HTTP server start error");
      mListener.onError();
    }
  }

  public void stopServer() {
    try {
      Log.d(LOG_TAG, "HTTP server stop");
      mServer.stop();
    } catch (Exception exception) {
      Log.d(LOG_TAG, "HTTP server stop error");
    }
  }

  // Return logo file Uri; a jpeg file
  public Uri createLogoFile(@NonNull Radio radio, int size) {
    String name = LOGO_FILE + ".jpg";
    try (FileOutputStream fileOutputStream = mContext.openFileOutput(name, Context.MODE_PRIVATE)) {
      Bitmap
        .createScaledBitmap(radio.getIcon(), size, size, false)
        .compress(Bitmap.CompressFormat.JPEG, 100, fileOutputStream);
    } catch (Exception exception) {
      Log.e(LOG_TAG, "createLogoFile: internal failure creating logo file");
    }
    Uri uri = getUri();
    return (uri == null) ? null : uri.buildUpon().appendEncodedPath(name).build();
  }

  @NonNull
  public Uri getRadioUri(@NonNull Radio radio) {
    return radio.getHandledUri(getUri());
  }

  private Uri getUri() {
    return NetworkTester.getUri(mContext, PORT);
  }

  public interface Listener {
    void onError();
  }
}