/*
 * Copyright (C) 2013 4th Line GmbH, Switzerland
 *
 * The contents of this file are subject to the terms of either the GNU
 * Lesser General Public License Version 2 or later ("LGPL") or the
 * Common Development and Distribution License Version 1 or later
 * ("CDDL") (collectively, the "License"). You may not use this file
 * except in compliance with the License. See LICENSE.txt for more
 * information.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.watea.radio_upnp.cling;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.watea.radio_upnp.service.HttpService;

import org.fourthline.cling.UpnpServiceImpl;
import org.fourthline.cling.android.AndroidRouter;
import org.fourthline.cling.android.AndroidUpnpService;
import org.fourthline.cling.controlpoint.ControlPoint;
import org.fourthline.cling.protocol.ProtocolFactory;
import org.fourthline.cling.registry.Registry;
import org.fourthline.cling.transport.Router;

/**
 * Provides a UPnP stack with Android configuration as an application service component.
 * <p>
 * Sends a search for all UPnP devices on instantiation. See the
 * {@link AndroidUpnpService} interface for a usage example.
 * </p>
 * <p/>
 *
 * @author Christian Bauer
 */
public class UpnpService extends Service {
  @Nullable
  private static HttpService.HttpServer httpServer = null;
  protected final Binder binder = new Binder();
  @Nullable
  protected org.fourthline.cling.UpnpService upnpService = null;
  @Nullable
  private UpnpServiceConfiguration upnpServiceConfiguration = null;

  // Must be called
  public static void setHttpServer(@NonNull HttpService.HttpServer httpServer) {
    UpnpService.httpServer = httpServer;
  }

  @Override
  public void onCreate() {
    super.onCreate();
    assert httpServer != null;
    upnpServiceConfiguration = new UpnpServiceConfiguration(httpServer);
    upnpService = new UpnpServiceImpl(upnpServiceConfiguration) {
      @NonNull
      @Override
      protected Router createRouter(ProtocolFactory protocolFactory, Registry registry) {
        return new AndroidRouter(getConfiguration(), protocolFactory, UpnpService.this);
      }

      @Override
      public synchronized void shutdown() {
        // First have to remove the receiver, so Android won't complain about it leaking
        // when the main UI thread exits
        ((AndroidRouter) getRouter()).unregisterBroadcastReceiver();
        // Now we can concurrently run the Cling shutdown code, without occupying the
        // Android main UI thread. This will complete probably after the main UI thread
        // is done.
        super.shutdown(true);
      }
    };
  }

  /**
   * Stops the UPnP service, when the last Activity unbinds from this Service.
   */
  @Override
  public void onDestroy() {
    if (upnpService != null) {
      upnpService.shutdown();
    }
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return binder;
  }

  public class Binder extends android.os.Binder implements AndroidUpnpService {
    @Override
    public org.fourthline.cling.UpnpService get() {
      return upnpService;
    }

    @Override
    public UpnpServiceConfiguration getConfiguration() {
      return upnpServiceConfiguration;
    }

    @Override
    public Registry getRegistry() {
      assert upnpService != null;
      return upnpService.getRegistry();
    }

    @Override
    public ControlPoint getControlPoint() {
      assert upnpService != null;
      return upnpService.getControlPoint();
    }
  }
}