/*
 * Copyright (c) 2018. Stephane Treuchot
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
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;

public class NetworkProxy {
  @Nullable
  private final ConnectivityManager connectivityManager;

  // Shall be called after onCreate
  public NetworkProxy(@NonNull Context context) {
    connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
  }

  public boolean isOnWifi() {
    return isOnNetworkCapability(NetworkCapabilities.TRANSPORT_WIFI);
  }

  // Only Wifi and Cellular is supported
  public boolean isDeviceOnline() {
    return isOnWifi() || isOnNetworkCapability(NetworkCapabilities.TRANSPORT_CELLULAR);
  }

  @Nullable
  public String getWifiIpAddress() {
    final NetworkCapabilities capabilities = getNetworkCapabilities();
    if ((capabilities == null) || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
      return null;
    }
    assert connectivityManager != null;
    final LinkProperties linkProperties = connectivityManager.getLinkProperties(getActiveNetwork());
    if (linkProperties == null) {
      return null;
    }
    String ipv6Fallback = null;
    for (final LinkAddress linkAddress : linkProperties.getLinkAddresses()) {
      final InetAddress inetAddress = linkAddress.getAddress();
      if (inetAddress.isLoopbackAddress() ||
        inetAddress.isAnyLocalAddress() ||
        inetAddress.isMulticastAddress() ||
        inetAddress.isLinkLocalAddress()) {
        continue;
      }
      // IPv4?
      if (inetAddress instanceof Inet4Address && inetAddress.isSiteLocalAddress()) {
        return inetAddress.getHostAddress();
      }
      // IPv6 fallback
      if (inetAddress instanceof Inet6Address) {
        assert inetAddress.getHostAddress() != null;
        ipv6Fallback = stripZoneId(inetAddress.getHostAddress());
      }
    }
    return ipv6Fallback;
  }

  public boolean isOnNetworkCapability(int networkCapability) {
    final NetworkCapabilities networkCapabilities = getNetworkCapabilities();
    return (networkCapabilities != null) && networkCapabilities.hasTransport(networkCapability);
  }

  @Nullable
  private Network getActiveNetwork() {
    if (connectivityManager == null) {
      return null;
    }
    return connectivityManager.getActiveNetwork();
  }

  @Nullable
  private NetworkCapabilities getNetworkCapabilities() {
    final Network activeNetwork = getActiveNetwork();
    if (activeNetwork == null) {
      return null;
    }
    assert connectivityManager != null;
    return connectivityManager.getNetworkCapabilities(activeNetwork);
  }

  @NonNull
  private String stripZoneId(@NonNull String address) {
    final int percent = address.indexOf('%');
    return (percent > 0) ? address.substring(0, percent) : address;
  }
}