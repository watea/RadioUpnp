package com.watea.radio_upnp.upnp;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import io.resourcepool.ssdp.model.SsdpService;

//  <device>
//    <deviceType>urn:schemas-upnp-org:device:deviceType:v</deviceType>
//    <friendlyName>short user-friendly title</friendlyName>
//    <manufacturer>manufacturer name</manufacturer>
//    <manufacturerURL>URL to manufacturer site</manufacturerURL>
//    <modelDescription>long user-friendly title</modelDescription>
//    <modelName>model name</modelName>
//    <modelNumber>model number</modelNumber>
//    <modelURL>URL to model site</modelURL>
//    <serialNumber>manufacturer's serial number</serialNumber>
//          <UDN>uuid:UUID</UDN>
//    <UPC>Universal Product Code</UPC>
//    <iconList>
//      <icon>
//        <mimetype>image/format</mimetype>
//        <width>horizontal pixels</width>
//        <height>vertical pixels</height>
//        <depth>color depth</depth>
//        <url>URL to icon</url>
//      </icon>
//    <!-- XML to declare other icons, if any, go here -->
//    </iconList>
//    <serviceList>
//      <service>
//        <serviceType>urn:schemas-upnp-org:service:serviceType:v</serviceType>
//        <serviceId>urn:upnp-org:serviceId:serviceID</serviceId>
//        <SCPDURL>URL to service description</SCPDURL>
//        <controlURL>URL for control</controlURL>
//        <eventSubURL>URL for eventing</eventSubURL>
//      </service>
//      <!-- Declarations for other services defined by a UPnP Forum working committee
//            (if any) go here -->
//      <!-- Declarations for other services added by UPnP vendor (if any) go here -->
//    </serviceList>
//    <deviceList>
//      <!-- Description of embedded devices defined by a UPnP Forum working committee
//          (if any) go here -->
//      <!-- Description of embedded devices added by UPnP vendor (if any) go here -->
//    </deviceList>
//    <presentationURL>URL for presentation</presentationURL>
//  </device>
public class Device extends Asset {
  private static final String LOG_TAG = Device.class.getName();
  private static final String XML_TAG = "device";
  private static final String DEVICE_LIST = "devicelist";
  private final SsdpService ssdpService;
  private final Set<Service> services = new HashSet<>();
  private final Set<Device> embeddedDevices = new HashSet<>();
  private final AtomicReference<Device> currentDevice = new AtomicReference<>();
  private final Callback isCompleteCallback = asset -> {
    if (isComplete()) {
      callback.onComplete(this);
    }
  };
  private boolean isEmbeddedDevices = false;
  private boolean isParseComplete;

  public Device(
    @NonNull SsdpService ssdpService,
    @NonNull Callback callback) throws IOException, XmlPullParserException {
    super(callback);
    this.ssdpService = ssdpService;
    // Fetch content
    // TODO: ajouter icon
    hydrate(new URLService(new URL(ssdpService.getLocation())));
  }

  @NonNull
  public Set<Device> getEmbeddedDevices() {
    return embeddedDevices;
  }

  @NonNull
  public Set<Service> getServices() {
    return services;
  }

  @Nullable
  public Service getService(@NonNull String serviceId) {
    return services.stream()
      .filter(service -> service.getServiceId().equals(serviceId))
      .findAny()
      .orElse(null);
  }

  @Override
  public void startAccept(@NonNull URLService urlService, @NonNull String currentTag)
    throws XmlPullParserException, IOException {
    switch (currentTag) {
      case DEVICE_LIST:
        isEmbeddedDevices = true;
        break;
      case XML_TAG:
        currentDevice.set(
          isEmbeddedDevices ? new Device(ssdpService, isCompleteCallback) : Device.this);
        break;
      default:
        // Nothing to do
    }
  }

  @Override
  public void endAccept (@NonNull URLService urlService, @NonNull String currentTag) {
    switch (currentTag) {
      case DEVICE_LIST:
        isEmbeddedDevices = false;
        break;
      case Service.XML_TAG:
        final String serviceType = urlService.getTag(Service.SERVICE_TYPE);
        final String serviceId = urlService.getTag(Service.SERVICE_ID);
        final String descriptionURL = urlService.getTag(Service.SCPDURL);
        final String controlURL = urlService.getTag(Service.CONTROL_URL);
        // No more tags for Service
        urlService.clearTags();
        if ((serviceType == null) ||
          (serviceId == null) ||
          (descriptionURL == null) ||
          (controlURL == null)) {
          Log.d(LOG_TAG, "endAccept: incomplete service parameters");
        } else {
          try {
            currentDevice.get().services.add(new Service(
              urlService.getURL(),
              serviceType,
              serviceId,
              descriptionURL,
              controlURL,
              isCompleteCallback));
          } catch
          (IOException | XmlPullParserException | URISyntaxException exception) {
            Log.d(LOG_TAG, "endAccept: service could not be created: " + serviceType, exception);
          }
        }
        break;
      case XML_TAG:
        final Device device = currentDevice.get();
        // Embedded device?
        if ((device != null) && isEmbeddedDevices) {
          embeddedDevices.add(device);
        }
        break;
      default:
        // Nothing to do
    }
  }

  // May be necessary if last service fails to complete
  @Override
  public void endParseAccept(@NonNull URLService uRLService) {
    isParseComplete = true;
    isCompleteCallback.onComplete(this);
  }

  @NonNull
  public SsdpService getSsdpService() {
    return ssdpService;
  }

  @NonNull
  public String getSerialNumber() {
    return ssdpService.getSerialNumber();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    final Device that = (Device) o;
    return ssdpService.equals(that.getSsdpService());
  }

  @Override
  public boolean isComplete() {
    return
      isParseComplete &&
        (services.stream().allMatch(Asset::isComplete) ||
          embeddedDevices.stream().allMatch(Asset::isComplete));
  }
}