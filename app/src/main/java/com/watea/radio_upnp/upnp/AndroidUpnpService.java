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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import io.resourcepool.ssdp.client.SsdpClient;
import io.resourcepool.ssdp.model.DiscoveryListener;
import io.resourcepool.ssdp.model.DiscoveryRequest;
import io.resourcepool.ssdp.model.SsdpRequest;
import io.resourcepool.ssdp.model.SsdpService;
import io.resourcepool.ssdp.model.SsdpServiceAnnouncement;

public class AndroidUpnpService extends android.app.Service {
  private static final String LOG_TAG = AndroidUpnpService.class.getName();
  private static final String DEVICE = "urn:schemas-upnp-org:device:MediaRenderer:1";
  private static final String SERVICE = "urn:schemas-upnp-org:ServiceId:AVTransport:1";
  private static final int DELAY = 60000; // ms
  private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
  private final Binder binder = new UpnpService();
  private final SsdpClient ssdpClient = SsdpClient.create();
  private final DiscoveryRequest discoveryRequestAll = SsdpRequest.discoverAll();
  private final DiscoveryRequest discoverMediaRenderer = SsdpRequest.builder()
    .serviceType(DEVICE)
    .build();
  private final Set<Device> devices = new HashSet<>();
  private final Set<Listener> listeners = new HashSet<>();
  private final DiscoveryListener discoveryListener = new DiscoveryListener() {
    @Override
    public void onServiceDiscovered(SsdpService service) {
      Log.d(LOG_TAG, "DiscoveryListener.onServiceDiscovered: found service: " + service);
      Log.d(LOG_TAG, "DiscoveryListener.onServiceDiscovered: found service: " + service.getServiceType());
      Log.d(LOG_TAG, "DiscoveryListener.onServiceDiscovered: found service: " + service.getOriginalResponse().toString());
      try {
        // Callback adds device when fully hydrated
        // TODO sauf les icones??
        new Device(service, device -> {
          Log.d(LOG_TAG, "Device added: " + ((Device) device).getDisplayString());
          listeners.forEach(listener -> listener.onDeviceAdd((Device) device));
        });
      } catch (IOException | XmlPullParserException exception) {
        Log.d(LOG_TAG, "DiscoveryListener.onServiceDiscovered: ", exception);
      }
    }

    @Override
    public void onServiceAnnouncement(SsdpServiceAnnouncement announcement) {
      Log.d(LOG_TAG, "DiscoveryListener.onServiceAnnouncement: Service announced something: " + announcement);
      final String remoteIP = announcement.getRemoteIp().toString();
      for (Device device : devices) {
        // Try to match
        // TODO à tester sinon servicetype et serialnumber
        if (remoteIP.equals(device.getSsdpService().getRemoteIp().toString())) {
          Log.d(LOG_TAG, "Device removed: " + device.getDisplayString());
          listeners.forEach(listener -> listener.onDeviceRemove(device));
          break;
        }
      }
    }

    @Override
    public void onFailed(Exception exception) {
      Log.d(LOG_TAG, "DiscoveryListener.onFailed: ", exception);
    }
  };
  private final Listener listener = new Listener() {
    @Override
    public void onDeviceAdd(@NonNull Device device) {
      devices.add(device);
    }

    @Override
    public void onDeviceRemove(@NonNull Device device) {
      devices.remove(device);
    }
  };

  @Override
  public void onCreate() {
    super.onCreate();
    executor.scheduleWithFixedDelay(this::searchAll, 0, DELAY, TimeUnit.MILLISECONDS);
    listeners.add(listener);
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    executor.shutdown();
    listeners.clear();
  }

  @Nullable
  @Override
  public IBinder onBind(Intent intent) {
    return binder;
  }

  public void searchAll() {
    ssdpClient.discoverServices(discoverMediaRenderer, discoveryListener);
  }

  public interface Listener {
    void onDeviceAdd(@NonNull Device device);

    void onDeviceRemove(@NonNull Device device);
  }

  public class UpnpService extends android.os.Binder {
    @NonNull
    public Set<Device> getDevices() {
      return devices;
    }

    public void search() {
      searchAll();
    }

    public void addListener(@NonNull Listener listener) {
      listeners.add(listener);
    }

    public void removeListener(@NonNull Listener listener) {
      listeners.remove(listener);
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
        for (Device device : devices) {
          final Device embeddedDevice = device.getEmbeddedDevice(uUID);
          if (embeddedDevice != null) {
            return embeddedDevice;
          }
        }
      }
      return result;
    }
  }
}