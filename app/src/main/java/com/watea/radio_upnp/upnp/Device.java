/*
 * Copyright (c) 2024-2026. Stephane Treuchot
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SuppressWarnings("unused")
//<?xml version="1.0" encoding="utf-8" ?>
//<root xmlns="urn:schemas-upnp-org:device-1-0">
//  <specVersion>
//    <major>1</major>
//    <minor>0</minor>
//  </specVersion>
//  <device>
//    <deviceType>urn:schemas-upnp-org:device:ZonePlayer:1</deviceType>
//    <friendlyName>172.16.0.10 - Sonos One - RINCON_48A6B88C948E01400</friendlyName>
//    <manufacturer>Sonos, Inc.</manufacturer>
//    <manufacturerURL>http://www.sonos.com</manufacturerURL>
//    <modelNumber>S18</modelNumber>
//<modelDescription>Sonos One</modelDescription>
//<modelName>Sonos One</modelName>
//    <modelURL>http://www.sonos.com/products/zoneplayers/S18</modelURL>
//    <softwareVersion>92.0-71170</softwareVersion>
//    <swGen>2</swGen>
//    <hardwareVersion>1.26.1.7-1.2</hardwareVersion>
//    <serialNum>removed for privacy</serialNum>
//    <MACAddress>removed for privacy</MACAddress>
//    <UDN>uuid:RINCON_48A6B88C948E01400</UDN>
//    <iconList>
//      <icon>
//        <id>0</id>
//        <mimetype>image/png</mimetype>
//        <width>48</width>
//        <height>48</height>
//        <depth>24</depth>
//        <url>/img/icon-S18.png</url>
//      </icon>
//    </iconList>
//    <minCompatibleVersion>91.0-00000</minCompatibleVersion>
//    <legacyCompatibleVersion>58.0-00000</legacyCompatibleVersion>
//    <apiVersion>1.49.1</apiVersion>
//    <minApiVersion>1.1.0</minApiVersion>
//    <displayVersion>17.7</displayVersion>
//    <extraVersion></extraVersion>
//    <nsVersion>42</nsVersion>
//    <versions>
//      <audioTxProtocol><version>3</version></audioTxProtocol>
//      <htAudioTxProtocol><version>7</version></htAudioTxProtocol>
//      <controlAPI><version>3.2.0</version><version>1.49.1</version></controlAPI>
//      <trueplaySDK><version>6</version></trueplaySDK>
//    </versions>
//    <roomName>Sonos</roomName>
//    <displayName>One</displayName>
//    <zoneType>20</zoneType>
//    <feature1>0x00000000</feature1>
//    <feature2>0x05c18332</feature2>
//    <feature3>0x0541580e</feature3>
//    <feature4>0x00000000</feature4>
//    <seriesid>A201</seriesid>
//    <variant>2</variant>
//    <internalSpeakerSize>5</internalSpeakerSize>
//    <memory>1024</memory>
//    <flash>1024</flash>
//    <ampOnTime>20</ampOnTime>
//    <retailMode>0</retailMode>
//    <SSLPort>1443</SSLPort>
//    <securehhSSLPort>1843</securehhSSLPort>
//    <serviceList>
//      <service>
//        <serviceType>urn:schemas-upnp-org:service:AlarmClock:1</serviceType>
//        <serviceId>urn:upnp-org:serviceId:AlarmClock</serviceId>
//        <controlURL>/AlarmClock/Control</controlURL>
//        <eventSubURL>/AlarmClock/Event</eventSubURL>
//        <SCPDURL>/xml/AlarmClock1.xml</SCPDURL>
//      </service>
//      <service>
//        <serviceType>urn:schemas-upnp-org:service:MusicServices:1</serviceType>
//        <serviceId>urn:upnp-org:serviceId:MusicServices</serviceId>
//        <controlURL>/MusicServices/Control</controlURL>
//        <eventSubURL>/MusicServices/Event</eventSubURL>
//        <SCPDURL>/xml/MusicServices1.xml</SCPDURL>
//      </service>
//      <service>
//        <serviceType>urn:schemas-upnp-org:service:DeviceProperties:1</serviceType>
//        <serviceId>urn:upnp-org:serviceId:DeviceProperties</serviceId>
//        <controlURL>/DeviceProperties/Control</controlURL>
//        <eventSubURL>/DeviceProperties/Event</eventSubURL>
//        <SCPDURL>/xml/DeviceProperties1.xml</SCPDURL>
//      </service>
//      <service>
//        <serviceType>urn:schemas-upnp-org:service:SystemProperties:1</serviceType>
//        <serviceId>urn:upnp-org:serviceId:SystemProperties</serviceId>
//        <controlURL>/SystemProperties/Control</controlURL>
//        <eventSubURL>/SystemProperties/Event</eventSubURL>
//        <SCPDURL>/xml/SystemProperties1.xml</SCPDURL>
//      </service>
//      <service>
//        <serviceType>urn:schemas-upnp-org:service:ZoneGroupTopology:1</serviceType>
//        <serviceId>urn:upnp-org:serviceId:ZoneGroupTopology</serviceId>
//        <controlURL>/ZoneGroupTopology/Control</controlURL>
//        <eventSubURL>/ZoneGroupTopology/Event</eventSubURL>
//        <SCPDURL>/xml/ZoneGroupTopology1.xml</SCPDURL>
//      </service>
//      <service>
//        <serviceType>urn:schemas-upnp-org:service:GroupManagement:1</serviceType>
//        <serviceId>urn:upnp-org:serviceId:GroupManagement</serviceId>
//        <controlURL>/GroupManagement/Control</controlURL>
//        <eventSubURL>/GroupManagement/Event</eventSubURL>
//        <SCPDURL>/xml/GroupManagement1.xml</SCPDURL>
//      </service>
//      <service>
//        <serviceType>urn:schemas-tencent-com:service:QPlay:1</serviceType>
//        <serviceId>urn:tencent-com:serviceId:QPlay</serviceId>
//        <controlURL>/QPlay/Control</controlURL>
//        <eventSubURL>/QPlay/Event</eventSubURL>
//        <SCPDURL>/xml/QPlay1.xml</SCPDURL>
//      </service>
//    </serviceList>
//    <deviceList>
//      <device>
//  <deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>
//  <friendlyName>172.16.0.10 - Sonos One Media Server - RINCON_48A6B88C948E01400</friendlyName>
//  <manufacturer>Sonos, Inc.</manufacturer>
//  <manufacturerURL>http://www.sonos.com</manufacturerURL>
//  <modelNumber>S18</modelNumber>
//<modelDescription>Sonos One Media Server</modelDescription>
//<modelName>Sonos One</modelName>
//  <modelURL>http://www.sonos.com/products/zoneplayers/S18</modelURL>
//  <UDN>uuid:RINCON_48A6B88C948E01400_MS</UDN>
//  <serviceList>
//    <service>
//      <serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType>
//      <serviceId>urn:upnp-org:serviceId:ContentDirectory</serviceId>
//      <controlURL>/MediaServer/ContentDirectory/Control</controlURL>
//      <eventSubURL>/MediaServer/ContentDirectory/Event</eventSubURL>
//      <SCPDURL>/xml/ContentDirectory1.xml</SCPDURL>
//    </service>
//    <service>
//      <serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
//	    <serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId>
//	    <controlURL>/MediaServer/ConnectionManager/Control</controlURL>
//	    <eventSubURL>/MediaServer/ConnectionManager/Event</eventSubURL>
//	    <SCPDURL>/xml/ConnectionManager1.xml</SCPDURL>
//	  </service>
//	</serviceList>
//      </device>
//      <device>
//	<deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
//  <friendlyName>Sonos - Sonos One Media Renderer - RINCON_48A6B88C948E01400</friendlyName>
//  <manufacturer>Sonos, Inc.</manufacturer>
//  <manufacturerURL>http://www.sonos.com</manufacturerURL>
//  <modelNumber>S18</modelNumber>
//<modelDescription>Sonos One Media Renderer</modelDescription>
//<modelName>Sonos One</modelName>
//  <modelURL>http://www.sonos.com/products/zoneplayers/S18</modelURL>
//	<UDN>uuid:RINCON_48A6B88C948E01400_MR</UDN>
//	<serviceList>
//	  <service>
//	    <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
//	    <serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>
//	    <controlURL>/MediaRenderer/RenderingControl/Control</controlURL>
//	    <eventSubURL>/MediaRenderer/RenderingControl/Event</eventSubURL>
//	    <SCPDURL>/xml/RenderingControl1.xml</SCPDURL>
//	  </service>
//	  <service>
//	    <serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
//	    <serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId>
//	    <controlURL>/MediaRenderer/ConnectionManager/Control</controlURL>
//	    <eventSubURL>/MediaRenderer/ConnectionManager/Event</eventSubURL>
//	    <SCPDURL>/xml/ConnectionManager1.xml</SCPDURL>
//	  </service>
//	  <service>
//	    <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
//	    <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
//	    <controlURL>/MediaRenderer/AVTransport/Control</controlURL>
//	    <eventSubURL>/MediaRenderer/AVTransport/Event</eventSubURL>
//	    <SCPDURL>/xml/AVTransport1.xml</SCPDURL>
//	  </service>
//	  <service>
//	    <serviceType>urn:schemas-sonos-com:service:Queue:1</serviceType>
//	    <serviceId>urn:sonos-com:serviceId:Queue</serviceId>
//	    <controlURL>/MediaRenderer/Queue/Control</controlURL>
//	    <eventSubURL>/MediaRenderer/Queue/Event</eventSubURL>
//	    <SCPDURL>/xml/Queue1.xml</SCPDURL>
//	  </service>
//      <service>
//        <serviceType>urn:schemas-upnp-org:service:GroupRenderingControl:1</serviceType>
//        <serviceId>urn:upnp-org:serviceId:GroupRenderingControl</serviceId>
//        <controlURL>/MediaRenderer/GroupRenderingControl/Control</controlURL>
//        <eventSubURL>/MediaRenderer/GroupRenderingControl/Event</eventSubURL>
//        <SCPDURL>/xml/GroupRenderingControl1.xml</SCPDURL>
//      </service>
//      <service>
//          <serviceType>urn:schemas-upnp-org:service:VirtualLineIn:1</serviceType>
//          <serviceId>urn:upnp-org:serviceId:VirtualLineIn</serviceId>
//          <controlURL>/MediaRenderer/VirtualLineIn/Control</controlURL>
//          <eventSubURL>/MediaRenderer/VirtualLineIn/Event</eventSubURL>
//          <SCPDURL>/xml/VirtualLineIn1.xml</SCPDURL>
//      </service>
//	</serviceList>
//        <X_Rhapsody-Extension xmlns="http://www.real.com/rhapsody/xmlns/upnp-1-0">
//  <deviceID>urn:rhapsody-real-com:device-id-1-0:sonos_1:RINCON_48A6B88C948E01400</deviceID>
//            <deviceCapabilities>
//              <interactionPattern type="real-rhapsody-upnp-1-0"/>
//            </deviceCapabilities>
//        </X_Rhapsody-Extension>
//        <qq:X_QPlay_SoftwareCapability xmlns:qq="http://www.tencent.com">QPlay:2</qq:X_QPlay_SoftwareCapability>
//        <iconList>
//          <icon>
//            <mimetype>image/png</mimetype>
//            <width>48</width>
//            <height>48</height>
//            <depth>24</depth>
//            <url>/img/icon-S18.png</url>
//          </icon>
//        </iconList>
//      </device>
//    </deviceList>
//  </device>
//</root>
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
  @NonNull
  private final URL location;
  @Nullable
  private volatile Device currentDevice = null;
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
    return (status != SsdpService.Status.BYEBYE) && (status != SsdpService.Status.EXPIRED);
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
        currentDevice = isEmbeddedDevices ? new Device(this) : this;
        break;
      default:
        // Nothing to do
    }
  }

  @Override
  public void endAccept(@NonNull URLService urlService, @NonNull String currentTag) {
    final Device device = currentDevice;
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
        if ((device != null) && (stringWidth != null) && (stringHeight != null) && (stringUrl != null)) {
          try {
            final URI uRI = new URI(stringUrl);
            final int width = Integer.parseInt(stringWidth);
            final int height = Integer.parseInt(stringHeight);
            final boolean isDefined = (device.icon != null);
            final boolean isIconSmaller = isDefined && (device.icon.getWidth() <= width) && (device.icon.getHeight() <= height);
            final boolean isPngUrlSignature = new URLService(location, uRI).isPngUrlSignature();
            if (!isDefined || isIconSmaller && (isPngUrlSignature || !device.isPngIcon)) {
              final Bitmap newIcon = new URLService(location, uRI).getBitmap();
              if (newIcon != null) {
                device.icon = newIcon;
                device.isPngIcon = isPngUrlSignature;
              }
            }
          } catch (IOException | URISyntaxException exception) {
            // Note: ignore exception, setOnError() not called here
            Log.e(LOG_TAG, "endAccept: fail to fetch icon", exception);
          }
        }
        break;
      case XML_TAG:
        if (device != null) {
          // Embedded device?
          if (isEmbeddedDevices) {
            embeddedDevices.add(device);
          }
          // Avoid stale currentDevice leaking into tags following this closing </device>
          currentDevice = null;
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