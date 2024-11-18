/*
 * Copyright (c) 2024. Stephane Treuchot
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

package com.watea.radio_upnp.upnp;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.watea.radio_upnp.service.NetworkProxy;
import com.watea.radio_upnp.ssdp.SsdpClient;
import com.watea.radio_upnp.ssdp.SsdpService;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.stream.Collectors;

public class AndroidUpnpService extends android.app.Service {
  private static final String LOG_TAG = AndroidUpnpService.class.getSimpleName();
  private final NetworkRequest networkRequest = new NetworkRequest.Builder()
    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) // Not a VPN
    .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) // Validated
    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) // Internet
    .build();
  private final Binder binder = new UpnpService();
  private final Set<Device> devices = new CopyOnWriteArraySet<>();
  private final ActionController actionController = new ActionController();
  private final Set<Listener> listeners = new HashSet<>();
  private final SsdpClient.Listener ssdpClientListener = new SsdpClient.Listener() {
    private final Device.Callback deviceCallback = new Device.Callback() {
      // Add device if has good DEVICE and not already known
      private synchronized void addDevice(@NonNull Device device) {
        if (device.getDeviceType().startsWith(SsdpClient.DEVICE) &&
          (device.getShortService(SsdpClient.AV_TRANSPORT_SERVICE_ID) != null) &&
          devices.stream().noneMatch(device::hasUUID)) {
          Log.d(LOG_TAG, "Device added: " + device.getDisplayString());
          devices.add(device);
          listeners.forEach(listener -> listener.onDeviceAdd(device));
        }
      }

      @Override
      public void onIcon(@NonNull Device device) {
        listeners.forEach(listener -> listener.onIcon(device));
      }

      @Override
      public void onComplete(@NonNull Asset asset) {
        final Device device = (Device) asset;
        final Set<Device> embeddedDevices = device.getEmbeddedDevices();
        Log.d(LOG_TAG, "Device found (embedded: " + embeddedDevices.size() + "): " + device.getDisplayString());
        // Add device and embedded devices
        addDevice(device);
        embeddedDevices.forEach(this::addDevice);
      }
    };

    private void addDevice(@NonNull SsdpService service) {
      new Thread(() -> {
        try {
          new Device(service, deviceCallback);
        } catch (IOException | XmlPullParserException exception) {
          Log.d(LOG_TAG, "onServiceDiscovered: ", exception);
        }
      }).start();
    }

    public void onServiceDiscovered(@NonNull SsdpService service) {
      Log.d(LOG_TAG, "Found SsdpService: " + service);
      addDevice(service);
    }

    @Override
    public void onServiceAnnouncement(@NonNull SsdpService service) {
      Log.d(LOG_TAG, "Announce SsdpService: " + service);
      final String uUID = service.getSerialNumber(); // Serial number and device UUID shall be identical
      final SsdpService.Status status = service.getStatus();
      final boolean isAlive = (status != SsdpService.Status.BYEBYE) && (status != SsdpService.Status.NONE);
      for (final Device device : devices) {
        if (device.hasUUID(uUID) && (device.isAlive() != isAlive)) {
          Log.d(LOG_TAG, "Device announcement: " + device.getDisplayString() + " => " + status);
          device.setAlive(isAlive);
          listeners.forEach(listener -> {
            final Set<Device> embeddedDevices = device.getEmbeddedDevices();
            if (isAlive) {
              listener.onDeviceAdd(device);
              embeddedDevices.forEach(listener::onDeviceAdd);
            } else {
              listener.onDeviceRemove(device);
              embeddedDevices.forEach(listener::onDeviceRemove);
            }
          });
          // Done!
          return;
        }
      }
      // Device not found, we add it
      if (status.equals(SsdpService.Status.ALIVE))
        addDevice(service);
    }

    @Override
    public void onFatalError() {
      Log.d(LOG_TAG, "onFatalError");
      listeners.forEach(Listener::onFatalError);
    }

    @Override
    public void onStop() {
      Log.d(LOG_TAG, "onStop");
      for (final Device device : devices) {
        listeners.forEach(listener -> {
          listener.onDeviceRemove(device);
          device.getEmbeddedDevices().forEach(listener::onDeviceRemove);
        });
      }
      devices.clear();
    }
  };
  private final SsdpClient ssdpClient = new SsdpClient(ssdpClientListener);
  private ConnectivityManager connectivityManager;
  private NetworkProxy networkProxy;
  private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
    @Override
    public void onAvailable(@NonNull Network network) {
      if (!ssdpClient.isStarted() && networkProxy.isOnWifi()) {
        devices.clear();
        ssdpClient.start();
      }
    }

    @Override
    public void onLost(@NonNull Network network) {
      if (!networkProxy.isOnWifi()) {
        ssdpClient.stop();
      }
    }
  };

  @Override
  public void onCreate() {
    super.onCreate();
    connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
    connectivityManager.registerNetworkCallback(networkRequest, networkCallback);
    networkProxy = new NetworkProxy(AndroidUpnpService.this);
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    connectivityManager.unregisterNetworkCallback(networkCallback);
    ssdpClient.stop();
    listeners.clear();
  }

  @Nullable
  @Override
  public IBinder onBind(Intent intent) {
    return binder;
  }

  public interface Listener {
    default void onDeviceAdd(@NonNull Device device) {
    }

    default void onDeviceRemove(@NonNull Device device) {
    }

    default void onIcon(@NonNull Device device) {
    }

    default void onFatalError() {
    }
  }

  public class UpnpService extends android.os.Binder {
    @NonNull
    public ActionController getActionController() {
      return actionController;
    }

    public void addListener(@NonNull Listener listener) {
      listeners.add(listener);
    }

    public void clearListeners() {
      listeners.clear();
    }

    @Nullable
    public Device getDevice(@NonNull String uUID) {
      return devices.stream().filter(device -> device.hasUUID(uUID)).findAny().orElse(null);
    }

    public Set<Device> getAliveDevices() {
      return devices.stream().filter(Device::isAlive).collect(Collectors.toSet());
    }
  }
}