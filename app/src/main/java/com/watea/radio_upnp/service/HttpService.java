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

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.watea.radio_upnp.R;
import com.watea.radio_upnp.model.Radio;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.server.handler.ResourceHandler;

import java.io.FileOutputStream;

public class HttpService extends Service {
  private static final String LOG_TAG = HttpService.class.getName();
  private static final String LOGO_FILE = "logo";
  private static final int REMOTE_LOGO_SIZE = 300;
  private final Server server = new Server(0);
  private final HandlerList handlers = new HandlerList();
  private final Binder binder = new Binder();
  @Nullable
  private RadioHandler radioHandler = null;

  @Override
  public void onCreate() {
    super.onCreate();
    // Handler for radio stream
    radioHandler = new RadioHandler(getString(R.string.app_name));
    // Handler for local files
    final ResourceHandler resourceHandler = new ResourceHandler();
    resourceHandler.setResourceBase(getFilesDir().getPath());
    // Add the ResourceHandler to the server
    addHandler(resourceHandler);
    addHandler(radioHandler);
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    try {
      Log.d(LOG_TAG, "HTTP server start");
      // Handlers are all defined here...
      server.setHandler(handlers);
      // ... so we can start
      server.start();
    } catch (Exception exception) {
      Log.d(LOG_TAG, "HTTP server start error", exception);
    }
    return super.onStartCommand(intent, flags, startId);
  }

  @Override
  public void onDestroy() {
    try {
      Log.d(LOG_TAG, "HTTP server stop");
      // Release RadioHandler if paused
      unlockRadioHandler();
      server.stop();
    } catch (Exception exception) {
      Log.i(LOG_TAG, "HTTP server stop error", exception);
    }
    super.onDestroy();
  }

  @Nullable
  @Override
  public IBinder onBind(Intent intent) {
    return binder;
  }

  private void addHandler(@NonNull Handler handler) {
    handlers.addHandler(handler);
  }

  private void unlockRadioHandler() {
    assert radioHandler != null;
    radioHandler.unlock();
  }

  public interface HttpServer {
    // Return logo file Uri; a jpeg file
    @Nullable
    Uri createLogoFile(@NonNull Context context, @NonNull Radio radio);

    @Nullable
    Uri getUri(@NonNull Context context);

    @NonNull
    Uri getLoopbackUri();

    void addHandler(@NonNull Handler handler);

    void bindRadioHandler(
      @NonNull RadioHandler.Listener radioHandlerListener,
      @NonNull RadioHandler.Callback radioHandlerCallback);

    void setRadioHandlerController(@Nullable RadioHandler.Controller radioHandlerController);

    void unlockRadioHandler();

    void stop();

    @NonNull
    Server getServer();
  }

  public class Binder extends android.os.Binder implements HttpServer {
    @Override
    // Return logo file Uri; a jpeg file
    @Nullable
    public Uri createLogoFile(@NonNull Context context, @NonNull Radio radio) {
      final String name = LOGO_FILE + radio.getId() + ".jpg";
      try {
        try (FileOutputStream fileOutputStream = context.openFileOutput(name, Context.MODE_PRIVATE)) {
          Bitmap
            .createScaledBitmap(radio.getIcon(), REMOTE_LOGO_SIZE, REMOTE_LOGO_SIZE, true)
            .compress(Bitmap.CompressFormat.JPEG, 100, fileOutputStream);
        }
      } catch (Exception exception) {
        Log.e(LOG_TAG, "createLogoFile: internal failure creating logo file", exception);
      }
      final Uri uri = getUri(context);
      return (uri == null) ? null : uri.buildUpon().appendEncodedPath(name).build();
    }

    @Override
    @Nullable
    public Uri getUri(@NonNull Context context) {
      return new NetworkProxy(context).getUri(getLocalPort());
    }

    @Override
    @NonNull
    public Uri getLoopbackUri() {
      return NetworkProxy.getLoopbackUri(getLocalPort());
    }

    @Override
    public void addHandler(@NonNull Handler handler) {
      HttpService.this.addHandler(handler);
    }

    @Override
    public void bindRadioHandler(
      @NonNull RadioHandler.Listener radioHandlerListener,
      @NonNull RadioHandler.Callback radioHandlerCallback) {
      assert radioHandler != null;
      radioHandler.bind(radioHandlerListener, radioHandlerCallback);
    }

    @Override
    public void setRadioHandlerController(@Nullable RadioHandler.Controller radioHandlerController) {
      assert radioHandler != null;
      radioHandler.setController(radioHandlerController);
    }

    @Override
    public void unlockRadioHandler() {
      HttpService.this.unlockRadioHandler();
    }

    @Override
    public void stop() {
      HttpService.this.stopSelf();
    }

    @NonNull
    @Override
    public Server getServer() {
      return server;
    }

    private int getLocalPort() {
      return ((ServerConnector) server.getConnectors()[0]).getLocalPort();
    }
  }
}