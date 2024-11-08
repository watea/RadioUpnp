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

package com.watea.radio_upnp.ssdp;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.net.InetAddress;
import java.util.Map;
import java.util.Objects;

@SuppressWarnings("unused")
public class SsdpServiceAnnouncement {
  @Nullable
  private final String serialNumber;
  @Nullable
  private final String serviceType;
  @Nullable
  private final String location;
  @Nullable
  private final Status status;
  @NonNull
  private final InetAddress remoteIp;
  @NonNull
  private final SsdpResponse originalResponse;

  public SsdpServiceAnnouncement(SsdpResponse response) {
    final Map<String, String> headers = response.getHeaders();
    this.serialNumber = headers.get("USN");
    this.serviceType = headers.get("NT");
    this.status = Status.parse(headers.get("NTS"));
    final String location = headers.get("LOCATION");
    this.location = (location == null) ? headers.get("AL") : location;
    this.remoteIp = response.getOriginAddress();
    this.originalResponse = response;
  }

  @Nullable
  public String getServiceType() {
    return serviceType;
  }

  @NonNull
  public InetAddress getRemoteIp() {
    return remoteIp;
  }

  @Nullable
  public String getLocation() {
    return location;
  }

  @Nullable
  public String getSerialNumber() {
    return serialNumber;
  }

  @NonNull
  public SsdpResponse getOriginalResponse() {
    return originalResponse;
  }

  public long getExpiry() {
    return originalResponse.getExpiry();
  }

  @Nullable
  public Status getStatus() {
    return status;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    final SsdpServiceAnnouncement that = (SsdpServiceAnnouncement) o;
    if (!Objects.equals(serialNumber, that.serialNumber))
      return false;
    if (!Objects.equals(serviceType, that.serviceType))
      return false;
    return status == that.status;
  }

  @Override
  public int hashCode() {
    int result = serialNumber != null ? serialNumber.hashCode() : 0;
    result = 31 * result + ((serviceType == null) ? 0 : serviceType.hashCode());
    result = 31 * result + ((status == null) ? 0 : status.hashCode());
    return result;
  }

  @NonNull
  @Override
  public String toString() {
    return "SsdpServiceAnnouncement{" +
      "serialNumber='" + serialNumber + '\'' +
      ", serviceType='" + serviceType + '\'' +
      ", location='" + location + '\'' +
      ", status=" + status +
      ", remoteIp=" + remoteIp +
      '}';
  }

  public enum Status {
    ALIVE, BYEBYE, UPDATE;

    // Parse NTS or ST header into a Status
    public static Status parse(@Nullable String nts) {
      if ("ssdp:alive".equals(nts)) {
        return ALIVE;
      }
      if ("ssdp:byebye".equals(nts)) {
        return BYEBYE;
      }
      if ("ssdp:update".equals(nts)) {
        return UPDATE;
      }
      return null;
    }
  }
}