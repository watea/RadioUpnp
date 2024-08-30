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

import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.stream.Collectors;

import io.resourcepool.ssdp.client.SsdpClient;
import io.resourcepool.ssdp.model.DiscoveryListener;
import io.resourcepool.ssdp.model.DiscoveryOptions;
import io.resourcepool.ssdp.model.DiscoveryRequest;
import io.resourcepool.ssdp.model.SsdpRequest;
import io.resourcepool.ssdp.model.SsdpService;
import io.resourcepool.ssdp.model.SsdpServiceAnnouncement;

public class AndroidUpnpService extends android.app.Service {
  private static final String LOG_TAG = AndroidUpnpService.class.getSimpleName();
  private static final String DEVICE = "urn:schemas-upnp-org:device:MediaRenderer:1";
  private static final Long PERIOD = 5000L;
  private final Binder binder = new UpnpService();
  private final SsdpClient ssdpClient = SsdpClient.create();
  private final DiscoveryOptions discoveryOptions = DiscoveryOptions.builder()
    .intervalBetweenRequests(PERIOD)
    .build();
  private final DiscoveryRequest discoverMediaRenderer = SsdpRequest.builder()
    .discoveryOptions(discoveryOptions)
    .build();
  private final Set<Device> devices = new CopyOnWriteArraySet<>();
  private final ActionController actionController = new ActionController();
  private final Set<Listener> listeners = new HashSet<>();
  private final DiscoveryListener discoveryListener = new DiscoveryListener() {
    private final Device.Callback deviceCallback = new Device.Callback() {
      @Override
      public void onIcon(@NonNull Device device) {
        listeners.forEach(listener -> listener.onIcon(device));
      }

      @Override
      public void onComplete(@NonNull Asset asset) {
        final Device device = (Device) asset;
        final String displayString = device.getDisplayString();
        Log.d(LOG_TAG, "Device found: " + displayString);
        // Reject if already known
        if (devices.stream().noneMatch(device::hasUUID)) {
          Log.d(LOG_TAG, "Device added: " + displayString);
          devices.add(device);
          listeners.forEach(listener -> listener.onDeviceAdd(device));
        }
      }
    };

    public void onServiceDiscovered(SsdpService service) {
      Log.d(LOG_TAG, "Found SsdpService: " + service);
      if (DEVICE.equals(service.getServiceType())) {
        try {
          new Device(service, deviceCallback);
        } catch (IOException | XmlPullParserException exception) {
          Log.d(LOG_TAG, "DiscoveryListener.onServiceDiscovered: ", exception);
        }
      }
    }

    @Override
    public void onServiceAnnouncement(SsdpServiceAnnouncement announcement) {
      Log.d(LOG_TAG, "SsdpServiceAnnouncement: " + announcement);
      final String uUID = announcement.getSerialNumber();
      final SsdpServiceAnnouncement.Status status = announcement.getStatus();
      final boolean isAlive = (status != SsdpServiceAnnouncement.Status.BYEBYE);
      devices.stream()
        .filter(device -> device.hasUUID(uUID) && (device.isAlive() != isAlive))
        .forEach(device -> {
          Log.d(LOG_TAG, "Device announcement: " + device.getDisplayString() + " => " + status);
          listeners.forEach(listener -> {
            if (isAlive) {
              listener.onDeviceAdd(device);
            } else {
              listener.onDeviceRemove(device);
            }
          });
        });
    }

    @Override
    public void onFailedAndIgnored(Exception exception) {
      Log.d(LOG_TAG, "DiscoveryListener.onFailedAndIgnored: ", exception);
    }

    @Override
    public void onFailed(Exception exception) {
      Log.d(LOG_TAG, "DiscoveryListener.onFailed: ", exception);
    }
  };

  @Override
  public void onCreate() {
    super.onCreate();
    ssdpClient.discoverServices(discoverMediaRenderer, discoveryListener);
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    ssdpClient.stopDiscovery();
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
      final Device result =
        devices.stream().filter(device -> device.hasUUID(uUID)).findAny().orElse(null);
      // Embedded devices?
      if (result == null) {
        for (final Device device : devices) {
          final Device embeddedDevice = device.getEmbeddedDevice(uUID);
          if (embeddedDevice != null) {
            return embeddedDevice;
          }
        }
      }
      return result;
    }

    public Set<Device> getAliveDevices() {
      return devices.stream().filter(Device::isAlive).collect(Collectors.toSet());
    }
  }
}