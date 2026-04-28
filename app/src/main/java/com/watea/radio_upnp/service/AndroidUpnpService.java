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

package com.watea.radio_upnp.service;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
import com.watea.radio_upnp.R;
import com.watea.radio_upnp.upnp.Device;
import com.watea.radio_upnp.upnp.RequestController;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class AndroidUpnpService extends android.app.Service {
  private static final String LOG_TAG = AndroidUpnpService.class.getSimpleName();
  private static final String DEVICE = "urn:schemas-upnp-org:device:MediaRenderer:";
  private static final String DEVICE_VERSION = "1";
  private static final String AV_TRANSPORT_SERVICE_ID = "AVTransport";
  // s, max time to wait for a device HTTP description fetch before giving up
  // => device HTTP description fetch may be slow on some renderers at startup
  private static final long DEVICE_FETCH_TIMEOUT_S = 20L;
  private final NetworkRequest networkRequest = new NetworkRequest.Builder()
    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) // Not a VPN
    .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) // Validated
    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) // Internet
    .build();
  private final Binder binder = new UpnpService();
  private final RequestController requestController = new RequestController();
  private final Devices devices = new Devices();
  // CopyOnWriteArraySet ensures thread-safe iteration and modification:
  // listener notifications are dispatched from background threads while
  // add/remove can happen from binder threads concurrently
  private final Set<Listener> listeners = new CopyOnWriteArraySet<>();
  private final ExecutorService deviceExecutor = Executors.newCachedThreadPool(); // Bounded executor for device HTTP fetches — prevents unbounded raw thread creation
  private final SsdpClient.Listener ssdpClientListener = new SsdpClientListener();
  private final SsdpClient ssdpClient = new SsdpClient(DEVICE + DEVICE_VERSION, ssdpClientListener);
  private final ConnectivityManager.NetworkCallback networkCallback = new NetworkCallback();
  private ConnectivityManager connectivityManager;
  private NetworkProxy networkProxy;
  private volatile boolean isDestroyed = false;

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
    // Tag we are destroyed
    isDestroyed = true;
    connectivityManager.unregisterNetworkCallback(networkCallback);
    // Clear listeners BEFORE stopping the SSDP client to prevent
    // spurious onDeviceRemove() notifications during shutdown
    listeners.clear();
    ssdpClient.stop();
    // Shut down the device fetch executor
    deviceExecutor.shutdownNow();
  }

  @Nullable
  @Override
  public IBinder onBind(Intent intent) {
    return binder;
  }

  private void tellRemoveListeners(@NonNull Device device) {
    // Pre-capture embedded devices while the set is still populated
    final List<Device> embedded = devices.getEmbeddedDevicesStream(device).collect(Collectors.toList());
    listeners.forEach(listener -> {
      listener.onDeviceRemove(device);
      embedded.forEach(listener::onDeviceRemove);
    });
  }

  private void tellAddListeners(@NonNull Device device) {
    // Pre-capture embedded devices while the set is still populated
    final List<Device> embedded = devices.getEmbeddedDevicesStream(device).collect(Collectors.toList());
    listeners.forEach(listener -> {
      listener.onDeviceAdd(device);
      embedded.forEach(listener::onDeviceAdd);
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
    public RequestController getRequestController() {
      return requestController;
    }

    public void addListener(@NonNull Listener listener) {
      devices.addListener(listener);
    }

    public void clearListeners() {
      listeners.clear();
    }

    public void setSelectedDeviceIdentity(@Nullable String selectedDeviceIdentity) {
      final Device previousDevice = getSelectedDevice();
      final SharedPreferences sharedPreferences = getSharedPreferences(getString(R.string.app_name), Context.MODE_PRIVATE);
      sharedPreferences.edit().putString(getString(R.string.key_selected_device), selectedDeviceIdentity).apply();
      tellSelectedDeviceIdentity(previousDevice);
    }

    public void tellSelectedDeviceIdentity(@Nullable Device previousDevice) {
      listeners.forEach(listener -> listener.onSelectedDeviceChange(previousDevice, getSelectedDevice()));
    }

    // Returns null if no device is persisted or if the persisted identity is no longer known
    @Nullable
    public Device getSelectedDevice() {
      final String selectedDeviceIdentity = getSharedPreferences(getString(R.string.app_name), Context.MODE_PRIVATE).getString(getString(R.string.key_selected_device), null);
      return (selectedDeviceIdentity == null) ? null : devices.get(selectedDeviceIdentity);
    }

    // Returns null if no device is selected OR if the selected device is no longer alive
    @Nullable
    public Device getActiveSelectedDevice() {
      final Device result = getSelectedDevice();
      return ((result == null) || result.isAlive()) ? result : null;
    }
  }

  private class SsdpClientListener implements SsdpClient.Listener {
    @Override
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
      // If we missed the onAvailable() because the client was still started
      if (!isDestroyed && networkProxy.isOnWifi()) {
        ssdpClient.start();
      }
    }
  }

  private class NetworkCallback extends ConnectivityManager.NetworkCallback {
    @Override
    public void onAvailable(@NonNull Network network) {
      final NetworkCapabilities cap = connectivityManager.getNetworkCapabilities(network);
      if (!ssdpClient.isStarted() && (cap != null) && cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
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
  }

  private class Devices extends HashSet<Device> {
    // Tracks UUIDs currently being fetched to prevent duplicate Device
    // construction when two SSDP announcements for the same UUID arrive in quick succession
    private final Set<String> pendingUUIDs = new HashSet<>();

    // Add device if it has the expected device type and is not already known
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
      final SsdpService.Status status = service.getStatus();
      final boolean isAlive = Device.isAlive(status);
      if (knownDevice == null) {
        // Skip BYEBYE/EXPIRED announcements for unknown devices — location is null
        // and there is nothing useful to fetch
        if (!isAlive) {
          return;
        }
        // Skip if this UUID is already being fetched asynchronously —
        // prevents duplicate Device objects from concurrent SSDP announcements
        if (!pendingUUIDs.add(uUID)) {
          Log.d(LOG_TAG, "Device fetch already in progress for UUID: " + uUID);
          return;
        }
        // Submit the blocking HTTP fetch to the bounded executor and
        // enforce a timeout via a second executor task to avoid hanging threads
        final Future<Device> future = deviceExecutor.submit(() -> new Device(service));
        deviceExecutor.execute(() -> {
          try {
            final Device device = future.get(DEVICE_FETCH_TIMEOUT_S, TimeUnit.SECONDS);
            final Set<Device> embeddedDevices = device.getEmbeddedDevices();
            Log.d(LOG_TAG, "Device found (embedded: " + embeddedDevices.size() + ", onError: " + device.isOnError() + "): " + device.getDisplayString());
            add(device);
            addAll(embeddedDevices);
          } catch (TimeoutException timeoutException) {
            Log.w(LOG_TAG, "Device fetch timed out: " + service);
            future.cancel(true);
          } catch (ExecutionException executionException) {
            Log.e(LOG_TAG, "process: add device failed!", executionException.getCause());
          } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
          } finally {
            // Always release the pending guard, even on failure, so
            // subsequent announcements for this UUID are not permanently blocked
            if (uUID != null) {
              synchronized (Devices.this) {
                pendingUUIDs.remove(uUID);
              }
            }
          }
        });
      } else if (knownDevice.isAlive() != isAlive) {
        Log.d(LOG_TAG, "Device announcement: " + knownDevice.getDisplayString() + " => " + status);
        knownDevice.setAlive(isAlive);
        if (isAlive) {
          tellAddListeners(knownDevice);
        } else {
          tellRemoveListeners(knownDevice);
        }
      }
    }

    @NonNull
    public synchronized Stream<Device> getEmbeddedDevicesStream(@NonNull Device device) {
      return device.getEmbeddedDevices().stream().filter(this::contains);
    }

    public synchronized void addListener(@NonNull Listener listener) {
      stream().filter(Device::isAlive).forEach(listener::onDeviceAdd);
      listeners.add(listener);
    }
  }
}