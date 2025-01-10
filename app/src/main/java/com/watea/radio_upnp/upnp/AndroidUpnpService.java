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

import com.watea.androidssdpclient.SsdpClient;
import com.watea.androidssdpclient.SsdpService;
import com.watea.radio_upnp.service.NetworkProxy;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

public class AndroidUpnpService extends android.app.Service {
  private static final String LOG_TAG = AndroidUpnpService.class.getSimpleName();
  private static final String DEVICE = "urn:schemas-upnp-org:device:MediaRenderer:";
  private static final String DEVICE_VERSION = "1";
  private static final String AV_TRANSPORT_SERVICE_ID = "AVTransport";
  private final NetworkRequest networkRequest = new NetworkRequest.Builder()
    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) // Not a VPN
    .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) // Validated
    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) // Internet
    .build();
  private final Binder binder = new UpnpService();
  private final ActionController actionController = new ActionController();
  private final Devices devices = new Devices();
  private final Set<Listener> listeners = new HashSet<>();
  private final SsdpClient.Listener ssdpClientListener = new SsdpClient.Listener() {
    public void onServiceDiscovered(@NonNull SsdpService service) {
      Log.d(LOG_TAG, "Found SsdpService: " + service);
      devices.process(service);
    }

    @Override
    public void onServiceAnnouncement(@NonNull SsdpService service) {
      Log.d(LOG_TAG, "Announce SsdpService: " + service);
      devices.process(service);
    }

    @Override
    public void onFatalError() {
      Log.d(LOG_TAG, "onFatalError");
      listeners.forEach(Listener::onFatalError);
    }

    @Override
    public void onStop() {
      Log.d(LOG_TAG, "onStop");
      devices.forEach(AndroidUpnpService.this::tellRemoveListeners);
      devices.clear();
    }
  };
  private final SsdpClient ssdpClient = new SsdpClient(DEVICE + DEVICE_VERSION, ssdpClientListener);
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
  @Nullable
  private String selectedDeviceIdentity = null;

  @Override
  public void onCreate() {
    super.onCreate();
    // Order matters
    networkProxy = new NetworkProxy(AndroidUpnpService.this);
    connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
    connectivityManager.registerNetworkCallback(networkRequest, networkCallback);
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

  private void tellRemoveListeners(@NonNull Device device) {
    listeners.forEach(listener -> {
      listener.onDeviceRemove(device);
      devices.getEmbeddedDevicesStream(device).forEach(listener::onDeviceRemove);
    });
  }

  private void tellAddListeners(@NonNull Device device) {
    listeners.forEach(listener -> {
      listener.onDeviceAdd(device);
      devices.getEmbeddedDevicesStream(device).forEach(listener::onDeviceAdd);
    });
  }

  public interface Listener {
    default void onDeviceAdd(@NonNull Device device) {
    }

    default void onDeviceRemove(@NonNull Device device) {
    }

    default void onFatalError() {
    }

    default void onSelectedDeviceChange(@Nullable Device previousDevice, @Nullable Device device) {
    }
  }

  public class UpnpService extends android.os.Binder {
    @NonNull
    public ActionController getActionController() {
      return actionController;
    }

    public void addListener(@NonNull Listener listener) {
      synchronized (devices) {
        devices.stream().filter(Device::isAlive).forEach(listener::onDeviceAdd);
        listeners.add(listener);
      }
    }

    public void clearListeners() {
      listeners.clear();
    }

    @Nullable
    public Device getDevice(@NonNull String uUID) {
      return devices.get(uUID);
    }

    public void setSelectedDeviceIdentity(@Nullable String selectedDeviceIdentity, boolean isInit) {
      final Device previousDevice = getSelectedDevice();
      AndroidUpnpService.this.selectedDeviceIdentity = selectedDeviceIdentity;
      if (!isInit) {
        listeners.forEach(listener -> listener.onSelectedDeviceChange(previousDevice, getSelectedDevice()));
      }
    }

    // Null if no valid device selected
    public Device getSelectedDevice() {
      final Device selectedDevice = (selectedDeviceIdentity == null) ? null : getDevice(selectedDeviceIdentity);
      // UPnP only allowed if device is alive
      return ((selectedDevice != null) && selectedDevice.isAlive()) ? selectedDevice : null;
    }
  }

  private class Devices extends HashSet<Device> {
    // Add device if has good DEVICE and not already known
    @Override
    public synchronized boolean add(Device device) {
      final boolean added = !device.isOnError() &&
        device.getDeviceType().startsWith(DEVICE) &&
        (device.getShortService(AV_TRANSPORT_SERVICE_ID) != null) &&
        super.add(device);
      if (added) {
        Log.d(LOG_TAG, "Device added: " + device.getDisplayString() + " => " + device.isAlive());
        if (device.isAlive()) {
          listeners.forEach(listener -> listener.onDeviceAdd(device));
        }
      }
      return added;
    }

    @Nullable
    public synchronized Device get(@NonNull String uUID) {
      return stream().filter(device -> device.hasUUID(uUID)).findFirst().orElse(null);
    }

    public synchronized void process(@NonNull SsdpService service) {
      final String uUID = Device.getUUID(service);
      final Device knownDevice = (uUID == null) ? null : get(uUID);
      if (knownDevice == null) {
        // Device not found, we build in own thread
        new Thread(() -> {
          try {
            final Device device = new Device(service);
            final Set<Device> embeddedDevices = device.getEmbeddedDevices();
            Log.d(LOG_TAG, "Device found (embedded: " + embeddedDevices.size() + ", onError: " + device.isOnError() + "): " + device.getDisplayString());
            add(device);
            addAll(embeddedDevices);
          } catch (IOException | XmlPullParserException exception) {
            Log.e(LOG_TAG, "process: add device failed!", exception);
          }
        }).start();
      } else {
        final boolean isAlive = Device.isAlive(service.getStatus());
        if (knownDevice.isAlive() != isAlive) {
          Log.d(LOG_TAG, "Device announcement: " + knownDevice.getDisplayString() + " => " + service.getStatus());
          knownDevice.setAlive(isAlive);
          if (isAlive) {
            tellAddListeners(knownDevice);
          } else {
            tellRemoveListeners(knownDevice);
          }
        }
      }
    }

    @NonNull
    public synchronized Stream<Device> getEmbeddedDevicesStream(@NonNull Device device) {
      return device.getEmbeddedDevices().stream().filter(this::contains);
    }
  }
}