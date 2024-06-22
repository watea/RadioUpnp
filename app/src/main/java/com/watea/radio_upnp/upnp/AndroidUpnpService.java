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
  private static final String LOG_TAG = AndroidUpnpService.class.getName();
  private static final String DEVICE = "urn:schemas-upnp-org:device:MediaRenderer:1";
  private static final String SERVICE = "urn:schemas-upnp-org:ServiceId:AVTransport:1";
  private final Binder binder = new UpnpService();
  private final SsdpClient ssdpClient = SsdpClient.create();
  private final DiscoveryRequest discoveryRequestAll = SsdpRequest.discoverAll();
  private final DiscoveryOptions discoveryOptions = DiscoveryOptions.builder()
    .intervalBetweenRequests(10000L)
    .build();
  private final DiscoveryRequest discoverMediaRenderer = SsdpRequest.builder()
    .discoveryOptions(discoveryOptions)
    .serviceType(DEVICE)
    .build();
  private final Set<Device> devices = new CopyOnWriteArraySet<>();
  private final Set<Listener> listeners = new HashSet<>();
  private final DiscoveryListener discoveryListener = new DiscoveryListener() {
    public void onServiceDiscovered(SsdpService service) {
      Log.d(LOG_TAG, "Found SsdpService: " + service);
      Log.d(LOG_TAG, "Found SsdpService: " + service.getServiceType());
      Log.d(LOG_TAG, "Found SsdpService: " + service.getOriginalResponse().toString());
      try {
        // Callback adds device when fully hydrated
        // TODO sauf les icones??
        new Device(service, asset -> {
          final Device device = (Device) asset;
          final String displayString = device.getDisplayString();
          Log.d(LOG_TAG, "Device found: " + displayString);
          // Reject if already known
          if (devices.stream().noneMatch(device::hasUUID)) {
            Log.d(LOG_TAG, "Device added: " + displayString);
            devices.add(device);
            listeners.forEach(listener -> listener.onDeviceAdd(device));
          }
        });
      } catch (IOException | XmlPullParserException exception) {
        Log.d(LOG_TAG, "DiscoveryListener.onServiceDiscovered: ", exception);
      }
    }

    @Override
    public void onServiceAnnouncement(SsdpServiceAnnouncement announcement) {
      Log.d(LOG_TAG, "SsdpServiceAnnouncement: " + announcement);
      final String uUID = announcement.getSerialNumber();
      final boolean isAlive = (announcement.getStatus() != SsdpServiceAnnouncement.Status.BYEBYE);
      devices.stream()
        .filter(device -> device.hasUUID(uUID) && (device.isAlive() != isAlive))
        .forEach(device -> {
          Log.d(LOG_TAG, "Device announcement: " + device.getDisplayString());
          listeners.forEach(listener -> {
            if (isAlive) {
              listener.onDeviceAdd(device);
            } else {
              listener.onDeviceRemove(device);
            }
            device.setAlive(isAlive);
          });
        });
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

    public Set<Device> getAliveDevices() {
      return devices.stream().filter(Device::isAlive).collect(Collectors.toSet());
    }
  }
}