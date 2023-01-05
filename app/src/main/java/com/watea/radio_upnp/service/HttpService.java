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
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.watea.radio_upnp.R;
import com.watea.radio_upnp.cling.UpnpService;
import com.watea.radio_upnp.model.Radio;
import com.watea.radio_upnp.model.RadioLibrary;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.fourthline.cling.android.AndroidUpnpService;

import java.io.FileOutputStream;

public class HttpService extends Service {
  private static final String LOG_TAG = HttpService.class.getName();
  private final Binder binder = new Binder();
  private AndroidUpnpService androidUpnpService;
  private final ServiceConnection upnpConnection = new ServiceConnection() {
    @Override
    public void onServiceConnected(ComponentName className, IBinder iBinder) {
      androidUpnpService = (AndroidUpnpService) iBinder;
    }

    @Override
    public void onServiceDisconnected(ComponentName className) {
      androidUpnpService = null;
    }
  };
  private RadioHandler radioHandler = null;
  private HttpServer httpServer = null;

  @Override
  public void onCreate() {
    super.onCreate();
    // Set HTTP server and bind to UPnP service (will launch server when ready)
    UpnpService.setHttpServer(httpServer = new HttpServer());
    if (!bindService(
      new Intent(this, UpnpService.class), upnpConnection, BIND_AUTO_CREATE)) {
      Log.e(LOG_TAG, "Internal failure; UpnpService not bound");
    }
  }

  @Override
  public void onDestroy() {
    // Release UPnP service (will stop HTTP server)
    unbindService(upnpConnection);
    // Force disconnection to release resources
    upnpConnection.onServiceDisconnected(null);
    super.onDestroy();
  }

  @Nullable
  @Override
  public IBinder onBind(Intent intent) {
    return binder;
  }

  public class HttpServer {
    private static final String LOGO_FILE = "logo";
    private static final int REMOTE_LOGO_SIZE = 300;
    private final HandlerList handlers = new HandlerList();
    @NonNull
    private final Server server;

    public HttpServer() {
      server = new Server(0);
      // Handler for radio stream
      radioHandler = new RadioHandler(getString(R.string.app_name));
      // Handler for local files
      final ResourceHandler resourceHandler = new ResourceHandler();
      resourceHandler.setResourceBase(getFilesDir().getPath());
      // Add the ResourceHandler to the server
      addHandler(resourceHandler);
      addHandler(radioHandler);
    }

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

    @Nullable
    public Uri getUri(@NonNull Context context) {
      return new NetworkProxy(context).getUri(getLocalPort());
    }

    @NonNull
    public Uri getLoopbackUri() {
      return NetworkProxy.getLoopbackUri(getLocalPort());
    }

    public void addHandler(@NonNull Handler handler) {
      handlers.addHandler(handler);
    }

    public void bindRadioHandler(
      @NonNull RadioHandler.Listener radioHandlerListener,
      @NonNull RadioLibrary.Provider radioLibraryProvider) {
      assert radioHandler != null;
      radioHandler.bind(radioHandlerListener, radioLibraryProvider);
    }

    public void setRadioHandlerController(@NonNull RadioHandler.Controller radioHandlerController) {
      assert radioHandler != null;
      radioHandler.setController(radioHandlerController);
    }

    public void resetRadioHandlerController() {
      assert radioHandler != null;
      radioHandler.resetController();
    }

    @NonNull
    public Server getServer() {
      return server;
    }

    public void startIfNotRunning() {
      if (!server.isStarted()) {
        try {
          Log.d(LOG_TAG, "HTTP server start");
          // Handlers are all defined here...
          server.setHandler(handlers);
          // ... so we can start
          server.start();
        } catch (Exception exception) {
          Log.d(LOG_TAG, "HTTP server start error", exception);
        }
      }
    }

    public void stopIfRunning() {
      try {
        Log.d(LOG_TAG, "HTTP server stop");
        // Release RadioHandler
        radioHandler.unBind();
        // Stop server
        if (server.isStarted()) {
          server.stop();
        }
      } catch (Exception exception) {
        Log.i(LOG_TAG, "HTTP server stop error", exception);
      }
    }

    private int getLocalPort() {
      return ((ServerConnector) server.getConnectors()[0]).getLocalPort();
    }
  }

  public class Binder extends android.os.Binder {
    @NonNull
    public HttpServer getHttpServer() {
      return httpServer;
    }

    @Nullable
    public AndroidUpnpService getAndroidUpnpService() {
      return androidUpnpService;
    }
  }
}