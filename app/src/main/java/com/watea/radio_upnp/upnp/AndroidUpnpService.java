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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.watea.radio_upnp.service.NetworkProxy;
import com.watea.radio_upnp.ssdp.SsdpClient;
import com.watea.radio_upnp.ssdp.SsdpService;
import com.watea.radio_upnp.ssdp.SsdpServiceAnnouncement;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.stream.Collectors;

public class AndroidUpnpService extends android.app.Service {
  private static final String LOG_TAG = AndroidUpnpService.class.getSimpleName();
  private static final String DEVICE = "urn:schemas-upnp-org:device:MediaRenderer:";
  private final Binder binder = new UpnpService();
  private final Set<Device> devices = new CopyOnWriteArraySet<>();
  private final ActionController actionController = new ActionController();
  private final Set<Listener> listeners = new HashSet<>();
  private final SsdpClient.Listener ssdpClientListener = new SsdpClient.Listener() {
    private final Device.Callback deviceCallback = new Device.Callback() {
      // Add device if of type DEVICE and not already known
      private void addDevice(@NonNull Device device) {
        if (device.getDeviceType().startsWith(DEVICE) && devices.stream().noneMatch(device::hasUUID)) {
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

    public void onServiceDiscovered(@NonNull SsdpService service) {
      Log.d(LOG_TAG, "Found SsdpService: " + service);
      new Thread(() -> {
        try {
          new Device(service, deviceCallback);
        } catch (IOException | XmlPullParserException exception) {
          Log.d(LOG_TAG, "DiscoveryListener.onServiceDiscovered: ", exception);
        }
      }).start();
    }

    @Override
    public void onServiceAnnouncement(@NonNull SsdpServiceAnnouncement announcement) {
      Log.d(LOG_TAG, "SsdpService Announcement: " + announcement);
      final String uUID = announcement.getSerialNumber();
      final SsdpServiceAnnouncement.Status status = announcement.getStatus();
      final boolean isAlive = (status != SsdpServiceAnnouncement.Status.BYEBYE);
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
    }

    @Override
    public void onFailed(@NonNull Exception exception) {
      Log.d(LOG_TAG, "DiscoveryListener.onFailed: ", exception);
    }

    @Override
    public void onStop() {
      for (final Device device : devices) {
        listeners.forEach(listener -> {
          listener.onDeviceRemove(device);
          device.getEmbeddedDevices().forEach(listener::onDeviceRemove);
        });
      }
    }
  };
  private final SsdpClient ssdpClient = new SsdpClient(ssdpClientListener);
  private final BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
      if (new NetworkProxy(AndroidUpnpService.this).hasWifiIpAddress()) {
        if (!ssdpClient.isStarted()) {
          devices.clear();
          ssdpClient.start();
        }
      } else {
        ssdpClient.stop();
      }
    }
  };

  @Override
  public void onCreate() {
    super.onCreate();
    registerReceiver(broadcastReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    unregisterReceiver(broadcastReceiver);
    ssdpClient.stop();
    listeners.clear();
  }

  @Nullable
  @Override
  public IBinder onBind(Intent intent) {
    return binder;
  }

  public interface Listener {
    void onDeviceAdd(@NonNull Device device);

    void onDeviceRemove(@NonNull Device device);

    void onIcon(@NonNull Device device);
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