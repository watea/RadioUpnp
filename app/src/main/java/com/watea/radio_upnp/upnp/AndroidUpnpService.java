package com.watea.radio_upnp.upnp;

import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.watea.radio_upnp.model.Radio;
import com.watea.radio_upnp.service.RadioURL;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Vector;
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
  private final Binder binder = new UpnpService();
  private final SsdpClient ssdpClient = SsdpClient.create();
  private final DiscoveryOptions discoveryOptions = DiscoveryOptions.builder()
    .intervalBetweenRequests(10000L)
    .build();
  private final DiscoveryRequest discoverMediaRenderer = SsdpRequest.builder()
    .discoveryOptions(discoveryOptions)
    .serviceType(DEVICE)
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
      Log.d(LOG_TAG, "Found SsdpService: " + service.getServiceType());
      Log.d(LOG_TAG, "Found SsdpService: " + service.getOriginalResponse().toString());
      try {
        new Device(service, deviceCallback);
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

  public interface Listener {
    void onDeviceAdd(@NonNull Device device);

    void onDeviceRemove(@NonNull Device device);

    void onIcon(@NonNull Device device);
  }

  public static class ActionController {
    private final Map<Radio, String> contentTypes = new Hashtable<>();
    private final Map<Device, List<String>> protocolInfos = new Hashtable<>();
    private final List<UpnpAction> upnpActions = new Vector<>();

    @Nullable
    public String getContentType(@NonNull Radio radio) {
      return contentTypes.get(radio);
    }

    @Nullable
    public List<String> getProtocolInfo(@NonNull Device device) {
      return protocolInfos.get(device);
    }

    // Can't be called on main thread
    public void fetchContentType(@NonNull Radio radio) {
      final String contentType = new RadioURL(radio.getURL()).getStreamContentType();
      if (contentType != null) {
        contentTypes.put(radio, contentType);
      }
    }

    public synchronized void release(boolean actionsOnly) {
      if (!actionsOnly) {
        contentTypes.clear();
        protocolInfos.clear();
      }
      upnpActions.clear();
    }

    public synchronized void runNextAction() {
      if (!upnpActions.isEmpty()) {
        upnpActions.remove(0);
        pullAction(false);
      }
    }

    public synchronized void schedule(@NonNull UpnpAction upnpAction) {
      upnpActions.add(upnpAction);
      // First action? => Start new thread
      if (upnpActions.size() == 1) {
        pullAction(true);
      }
    }

    // Do nothing if list is empty
    public void putProtocolInfo(@NonNull Device device, @NonNull List<String> list) {
      if (!list.isEmpty()) {
        protocolInfos.put(device, list);
      }
    }

    private void pullAction(boolean isOnOwnThread) {
      if (!upnpActions.isEmpty()) {
        upnpActions.get(0).execute(isOnOwnThread);
      }
    }
  }

  public class UpnpService extends android.os.Binder {
    @NonNull
    public Set<Device> getDevices() {
      return devices;
    }

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