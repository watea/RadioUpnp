package com.watea.radio_upnp.upnp;

import android.util.Log;

import androidx.annotation.NonNull;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import io.resourcepool.ssdp.client.SsdpClient;
import io.resourcepool.ssdp.model.DiscoveryListener;
import io.resourcepool.ssdp.model.DiscoveryRequest;
import io.resourcepool.ssdp.model.SsdpRequest;
import io.resourcepool.ssdp.model.SsdpService;
import io.resourcepool.ssdp.model.SsdpServiceAnnouncement;

public class UpnpService {
  private static final String LOG_TAG = UpnpService.class.getName();
  private static final String DEVICE = "urn:schemas-upnp-org:device:MediaRenderer:1";
  private static final String SERVICE = "urn:schemas-upnp-org:ServiceId:AVTransport:1";
  private final SsdpClient ssdpClient = SsdpClient.create();
  private final DiscoveryRequest discoveryRequestAll = SsdpRequest.discoverAll();
  private final DiscoveryRequest discoverMediaRenderer = SsdpRequest.builder()
    .serviceType(DEVICE)
    .build();
  private final Set<Device> devices = new HashSet<>();
  private final Callback callback;

  public UpnpService(@NonNull Callback callback) {
    this.callback = callback;
  }

  @NonNull
  public Set<Device> getDevices() {
    return devices;
  }

  public void searchAll() {
    ssdpClient.discoverServices(discoverMediaRenderer, new DiscoveryListener() {
      @Override
      public void onServiceDiscovered(SsdpService service) {
        Log.d(LOG_TAG, "onServiceDiscovered: found service:" + service);
        Log.d(LOG_TAG, "onServiceDiscovered: found service:" + service.getServiceType());
        Log.d(LOG_TAG, "onServiceDiscovered: found service:" + service.getOriginalResponse().toString());
        try {
          // Callback adds device when fully hydrated
          // TODO sauf les icones??
          new Device(service, device -> {
            devices.add((Device) device);
            callback.onNewDevice((Device) device);
          });
        } catch (IOException | XmlPullParserException exception) {
          Log.d(LOG_TAG, "onServiceDiscovered: ", exception);
        }
      }

      @Override
      public void onServiceAnnouncement(SsdpServiceAnnouncement announcement) {
        Log.d(LOG_TAG, "onServiceAnnouncement: Service announced something:" + announcement);
        final String remoteIP = announcement.getRemoteIp().toString();
        for (Device device : devices) {
          // Try to match
          // TODO à tester sinon servicetype et serialnumber
          if (remoteIP.equals(device.getSsdpService().getRemoteIp().toString())) {
            devices.remove(device);
            callback.onRemoveDevice(device);
            break;
          }
        }
      }

      @Override
      public void onFailed(Exception ex) {
        Log.d(LOG_TAG, "DiscoveryListener.onFailed:", ex);
      }
    });
  }

  public interface Callback {
    void onNewDevice(@NonNull Device device);
    void onRemoveDevice(@NonNull Device device);
  }
}