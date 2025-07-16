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

import android.graphics.Bitmap;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.watea.androidssdpclient.SsdpService;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SuppressWarnings("unused")
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
  private static final String DEVICE_TYPE = "deviceType";
  private static final String FRIENDLY_NAME = "friendlyName";
  private static final String MODEL_NAME = "modelName";
  private static final String MODEL_NUMBER = "modelNumber";
  private static final String UDN = "UDN";
  private static final String ICON = "icon";
  private static final String WIDTH = "width";
  private static final String HEIGHT = "height";
  private static final String URL = "url";
  private static final String UUID_PATTERN = "^(.*)::";
  private static final Pattern UUID_REGEX = Pattern.compile(UUID_PATTERN);
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
  private String deviceType = null;
  @Nullable
  private String friendlyName = null;
  @Nullable
  private String modelName = null;
  @Nullable
  private String modelNumber = null;
  @Nullable
  private String uUID = null;
  private boolean isAlive = true;
  private boolean isEmbeddedDevices = false;
  @Nullable
  private Bitmap icon = null;
  private boolean isPngIcon = false;

  public Device(@NonNull SsdpService ssdpService) throws IOException, XmlPullParserException {
    this.ssdpService = ssdpService;
    this.superDevice = null;
    location = new URL(ssdpService.getLocation());
    hydrate(new URLService(location));
  }

  // Embedded device
  public Device(@NonNull Device device) {
    this.ssdpService = device.ssdpService;
    this.superDevice = device;
    this.location = device.location;
  }

  @Nullable
  public static String getUUID(@NonNull SsdpService ssdpService) {
    final String serialNumber = ssdpService.getSerialNumber();
    if (serialNumber == null) {
      return null;
    } else {
      final Matcher matcher = UUID_REGEX.matcher(serialNumber);
      return (matcher.find()) ? matcher.group(1) : null;
    }
  }

  public static boolean isAlive(@NonNull SsdpService.Status status) {
    return (status != SsdpService.Status.BYEBYE);
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
    final Device device = currentDevice.get();
    switch (currentTag) {
      case DEVICE_LIST:
        isEmbeddedDevices = false;
        break;
      case DEVICE_TYPE:
        if (device != null) {
          device.deviceType = urlService.getTag(DEVICE_TYPE);
        }
        break;
      case FRIENDLY_NAME:
        if (device != null) {
          device.friendlyName = urlService.getTag(FRIENDLY_NAME);
        }
        break;
      case MODEL_NAME:
        if (device != null) {
          device.modelName = urlService.getTag(MODEL_NAME);
        }
        break;
      case MODEL_NUMBER:
        if (device != null) {
          device.modelNumber = urlService.getTag(MODEL_NUMBER);
        }
        break;
      case UDN:
        if (device != null) {
          device.uUID = urlService.getTag(UDN);
        }
        break;
      case Service.XML_TAG:
        final String serviceType = urlService.getTag(Service.SERVICE_TYPE);
        final String serviceId = urlService.getTag(Service.SERVICE_ID);
        final String descriptionURL = urlService.getTag(Service.SCPDURL);
        final String controlURL = urlService.getTag(Service.CONTROL_URL);
        // No more tags for Service
        urlService.clearTags();
        if ((device == null) ||
          (serviceType == null) ||
          (serviceId == null) ||
          (descriptionURL == null) ||
          (controlURL == null)) {
          setOnError();
          Log.e(LOG_TAG, "endAccept: incomplete service parameters");
        } else {
          final String log = "Add service: " + serviceType + " to " + getDisplayString();
          try {
            final Service service = new Service(
              this,
              urlService.getURL(),
              serviceType,
              serviceId,
              new URI(descriptionURL),
              new URI(controlURL));
            if (service.isOnError()) {
              setOnError();
              Log.e(LOG_TAG, log + " failed");
            } else {
              device.services.add(service);
              Log.d(LOG_TAG, log);
            }
          } catch (IOException | XmlPullParserException | URISyntaxException exception) {
            setOnError();
            Log.e(LOG_TAG, log + " failed", exception);
          }
        }
        break;
      case ICON:
        // Fetch icon if larger than existing one.
        // PNG format is preferred.
        final String stringWidth = urlService.getTag(WIDTH);
        final String stringHeight = urlService.getTag(HEIGHT);
        final String stringUrl = urlService.getTag(URL);
        if ((stringWidth != null) && (stringHeight != null) && (stringUrl != null)) {
          try {
            final URI uRI = new URI(stringUrl);
            final int width = Integer.parseInt(stringWidth);
            final int height = Integer.parseInt(stringHeight);
            final boolean isDefined = (icon != null);
            final boolean isIconSmaller = isDefined && (icon.getWidth() <= width) && (icon.getHeight() <= height);
            final boolean isPngUrlSignature = new URLService(location, uRI).isPngUrlSignature();
            if (!isDefined || isIconSmaller && (isPngUrlSignature || !isPngIcon)) {
              final Bitmap newIcon = new URLService(location, uRI).getBitmap();
              if (newIcon != null) {
                icon = newIcon;
                isPngIcon = isPngUrlSignature;
              }
            }
          } catch (IOException | URISyntaxException exception) {
            // Note: ignore exception, setOnError() not called here
            Log.e(LOG_TAG, "endAccept: fail to fetch icon", exception);
          }
        }
        break;
      case XML_TAG:
        // Embedded device?
        if ((device != null) && isEmbeddedDevices) {
          embeddedDevices.add(device);
        }
        break;
      default:
        // Nothing to do
    }
  }

  @Override
  public void endParseAccept(@NonNull URLService uRLService) {
    // Some device have no UUID (tag UDN) in XML, so we take it from SSDP response
    uUID = (uUID == null) ? getUUID(ssdpService) : uUID;
    // Alive?
    isAlive = isAlive(ssdpService.getStatus());
  }

  @NonNull
  public SsdpService getSsdpService() {
    return ssdpService;
  }

  @Nullable
  public String getUUID() {
    return uUID;
  }

  public boolean hasUUID(@Nullable String otherUUID) {
    return (uUID != null) && uUID.equals(otherUUID);
  }

  public boolean hasUUID(@NonNull Device device) {
    return hasUUID(device.uUID);
  }

  @NonNull
  public String getDeviceType() {
    return getTag(deviceType);
  }

  @NonNull
  public String getDisplayString() {
    return getTag(friendlyName);
  }

  @Nullable
  public Bitmap getIcon() {
    return icon;
  }

  @Override
  public boolean equals(@Nullable Object obj) {
    if (this == obj) return true;
    if ((obj == null) || (getClass() != obj.getClass())) return false;
    return hasUUID((Device) obj);
  }

  @Override
  public int hashCode() {
    return (uUID == null) ? super.hashCode() : uUID.hashCode();
  }
}