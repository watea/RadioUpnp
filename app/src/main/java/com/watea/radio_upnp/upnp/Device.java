package com.watea.radio_upnp.upnp;

import android.graphics.Bitmap;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.net.URI;
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
  private static final String LOG_TAG = Device.class.getSimpleName();
  private static final String XML_TAG = "device";
  private static final String DEVICE_LIST = "deviceList";
  private static final String SERVICE_NAME_SPACE = "urn:upnp-org:serviceId:";
  private static final String MODEL_NAME = "modelName";
  private static final String MODEL_NUMBER = "modelNumber";
  private static final String UDN = "UDN";
  private static final String ICON = "icon";
  private static final String WIDTH = "width";
  private static final String HEIGHT = "height";
  private static final String URL = "url";
  @NonNull
  private final SsdpService ssdpService;
  @Nullable
  private final Device superDevice;
  private final Set<Service> services = new HashSet<>();
  private final Set<Device> embeddedDevices = new HashSet<>();
  private final AtomicReference<Device> currentDevice = new AtomicReference<>();
  @NonNull
  private final URL location;
  @Nullable
  private String modelName = null;
  @Nullable
  private String modelNumber = null;
  @Nullable
  private String uUID = null;
  private boolean isAlive = true;
  private boolean isEmbeddedDevices = false;
  private boolean isParseComplete;
  private final Asset.Callback isCompleteCallback = asset -> {
    if (isComplete()) {
      callback.onComplete(this);
    }
  };
  @Nullable
  private Bitmap icon = null;

  public Device(
    @NonNull SsdpService ssdpService,
    @NonNull Callback callback) throws IOException, XmlPullParserException {
    super(callback);
    this.ssdpService = ssdpService;
    this.superDevice = null;
    location = new URL(ssdpService.getLocation());
    hydrate(new URLService(location));
  }

  // Embedded device
  public Device(
    @NonNull Device device) {
    this.ssdpService = device.ssdpService;
    this.superDevice = device;
    this.location = device.location;
  }

  public boolean isAlive() {
    return isAlive;
  }

  public void setAlive(boolean alive) {
    isAlive = alive;
  }

  public boolean isEmbeddedDevice() {
    return (superDevice != null);
  }

  @NonNull
  public Set<Device> getEmbeddedDevices() {
    return embeddedDevices;
  }

  @Nullable
  public Device getEmbeddedDevice(@NonNull String uUID) {
    return embeddedDevices.stream().filter(device -> device.hasUUID(uUID)).findAny().orElse(null);
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

  @Nullable
  public Service getShortService(@NonNull String serviceId) {
    return getService(SERVICE_NAME_SPACE + serviceId);
  }

  @Override
  public void startAccept(@NonNull URLService urlService, @NonNull String currentTag) {
    switch (currentTag) {
      case DEVICE_LIST:
        isEmbeddedDevices = true;
        break;
      case XML_TAG:
        currentDevice.set(isEmbeddedDevices ? new Device(this) : this);
        break;
      default:
        // Nothing to do
    }
  }

  @Override
  public void endAccept(@NonNull URLService urlService, @NonNull String currentTag) {
    switch (currentTag) {
      case DEVICE_LIST:
        isEmbeddedDevices = false;
        break;
      case MODEL_NAME:
        currentDevice.get().modelName = urlService.getTag(MODEL_NAME);
        break;
      case MODEL_NUMBER:
        currentDevice.get().modelNumber = urlService.getTag(MODEL_NUMBER);
        break;
      case UDN:
        currentDevice.get().uUID = urlService.getTag(UDN);
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
          Log.e(LOG_TAG, "endAccept: incomplete service parameters");
        } else {
          try {
            currentDevice.get().services.add(new Service(
              this,
              urlService.getURL(),
              serviceType,
              serviceId,
              descriptionURL,
              controlURL,
              isCompleteCallback));
            Log.d(LOG_TAG, "Add service: " + serviceType + " to " + getDisplayString());
          } catch
          (IOException | XmlPullParserException | URISyntaxException exception) {
            Log.e(LOG_TAG, "endAccept: service could not be created: " + serviceType, exception);
          }
        }
        break;
      case ICON:
        final String stringWidth = urlService.getTag(WIDTH);
        final String stringHeigth = urlService.getTag(HEIGHT);
        final String stringUrl = urlService.getTag(URL);
        if ((stringWidth != null) && (stringHeigth != null) && (stringUrl != null)) {
          final int width = Integer.parseInt(stringWidth);
          final int height = Integer.parseInt(stringHeigth);
          if ((icon == null) || (icon.getWidth() < width) && (icon.getHeight() < height)) {
            try {
              fetchIcon(new URI(stringUrl));
            } catch (Exception exception) {
              Log.e(LOG_TAG, "endAccept: fail to fetch icon", exception);
            }
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

  @Override
  public boolean isComplete() {
    return
      isParseComplete &&
        !services.isEmpty() &&
        (services.stream().allMatch(Asset::isComplete) ||
          embeddedDevices.stream().allMatch(Asset::isComplete));
  }

  @NonNull
  public SsdpService getSsdpService() {
    return ssdpService;
  }

  @Nullable
  public String getUUID() {
    return uUID;
  }

  public boolean hasUUID(@Nullable String uUID) {
    return (this.uUID != null) && this.uUID.equals(uUID);
  }

  public boolean hasUUID(@NonNull Device device) {
    return hasUUID(device.uUID);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if ((o == null) || (getClass() != o.getClass())) return false;
    final Device that = (Device) o;
    return ssdpService.equals(that.getSsdpService());
  }

  @NonNull
  public String getDisplayString() {
    return modelName + ((modelNumber == null) ? "" : (": " + modelNumber));
  }

  @Nullable
  public Bitmap getIcon() {
    return icon;
  }

  private void fetchIcon(@NonNull URI uRI) throws IOException, URISyntaxException {
    icon = new URLService(location, uRI).getBitmap();
    if (icon != null) {
      ((Device.Callback) callback).onIcon(this);
    }
  }

  public interface Callback extends Asset.Callback {
    void onIcon(@NonNull Device device);
  }
}