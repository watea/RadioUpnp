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

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.servlet.ServletContextHandler;

import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Vector;

public class HttpService extends Service {
  private static final String LOG_TAG = HttpService.class.getName();
  private final Binder binder = new Binder();
  private final List<ServiceConnection> upnpConnections = new Vector<>();
  private UpnpService.Binder upnpServiceBinder = null;
  private final ServiceConnection upnpConnection = new ServiceConnection() {
    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
      upnpServiceBinder = (UpnpService.Binder) service;
      upnpConnections.forEach(connection -> connection.onServiceConnected(name, service));
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
      upnpConnections.forEach(connection -> connection.onServiceDisconnected(name));
      upnpConnections.clear();
      upnpServiceBinder = null;
    }
  };
  private HttpServer httpServer = null;

  @Override
  public void onCreate() {
    super.onCreate();
    Log.d(LOG_TAG, "onCreate");
    // Set HTTP server
    UpnpService.setHttpServer(httpServer = new HttpServer());
    // Now we can bind to UPnP service
//    if (!bindService(new Intent(this, UpnpService.class), upnpConnection, BIND_AUTO_CREATE)) {
//      Log.e(LOG_TAG, "Internal failure; HttpService not bound");
//    }
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    Log.d(LOG_TAG, "onDestroy");
    // Release UPnP service
    unbindService(upnpConnection);
    // Force disconnection to release resources
    upnpConnection.onServiceDisconnected(null);
    // Release server
    httpServer.stop();
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
    // Handler for radio stream
    private final RadioHandler radioHandler = new RadioHandler(getString(R.string.app_name));
    private final Server server = new Server(0);

    public HttpServer() {
      // Handler for local files
      final ResourceHandler resourceHandler = new ResourceHandler();
      resourceHandler.setResourceBase(getFilesDir().getPath());
      // Add the ResourceHandler to the server
      handlers.addHandler(resourceHandler);
      //handlers.addHandler(radioHandler);
    }

    // Return logo file Uri; a jpeg file
    @Nullable
    public Uri createLogoFile(@NonNull Context context, @NonNull Radio radio) {
      final String name = LOGO_FILE + radio.hashCode() + ".jpg";
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

    public void bindRadioHandler(@NonNull RadioHandler.Listener radioHandlerListener) {
      radioHandler.bind(radioHandlerListener);
    }

    public void setRadioHandlerController(@NonNull RadioHandler.Controller radioHandlerController) {
      radioHandler.setController(radioHandlerController);
    }

    public void resetRadioHandlerController() {
      radioHandler.resetController();
    }

    public void startIfNotRunning() {
      Log.d(LOG_TAG, "startIfNotRunning");
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

    public void stop() {
      Log.d(LOG_TAG, "stop");
      try {
        // Release RadioHandler
        radioHandler.unBind();
        // Stop server
        server.stop();
      } catch (Exception exception) {
        Log.i(LOG_TAG, "HTTP server stop error", exception);
      }
    }

    public void registryClean() {
      if (upnpServiceBinder != null) {
        upnpServiceBinder.getControlPoint().getRegistry().removeAllRemoteDevices();
      }
    }

    public void setServletHandler(@NonNull ServletContextHandler servletHandler) {
      if (Arrays.stream(handlers.getHandlers())
        .noneMatch(handler -> handler instanceof ServletContextHandler)) {
        handlers.addHandler(servletHandler);
      }
    }

    @NonNull
    public ServerConnector getConnector() {
      return (ServerConnector) server.getConnectors()[0];
    }

    private int getLocalPort() {
      return getConnector().getLocalPort();
    }
  }

  public class Binder extends android.os.Binder {
    @NonNull
    public HttpServer getHttpServer() {
      return httpServer;
    }

    public void addUpnpConnection(@NonNull ServiceConnection upnpConnection) {
      upnpConnections.add(upnpConnection);
      // Connect UPnP service if already up
      if (upnpServiceBinder != null) {
        upnpConnection.onServiceConnected(null, upnpServiceBinder);
      }
    }

    public void removeUpnpConnection(@NonNull ServiceConnection upnpConnection) {
      upnpConnection.onServiceDisconnected(null);
      upnpConnections.remove(upnpConnection);
    }
  }
}