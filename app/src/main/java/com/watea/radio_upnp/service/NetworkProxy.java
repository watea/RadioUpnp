/*
 * Copyright (c) 2018-2026. Stephane Treuchot
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
import android.net.InetAddresses;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;

public class NetworkProxy {
  @Nullable
  private final ConnectivityManager connectivityManager;

  // Shall be called after onCreate
  public NetworkProxy(@NonNull Context context) {
    connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
  }

  // Literal private/link-local addresses and mDNS names only: other names would need a DNS lookup
  @RequiresApi(api = Build.VERSION_CODES.Q)
  public static boolean isLocalHost(@Nullable String host) {
    if (host == null) {
      return false;
    }
    if (host.endsWith(".local")) {
      return true;
    }
    if (!InetAddresses.isNumericAddress(host)) {
      return false;
    }
    final InetAddress address = InetAddresses.parseNumericAddress(host);
    return address.isSiteLocalAddress() ||
      address.isLinkLocalAddress() ||
      // IPv6 unique local fc00::/7
      ((address instanceof Inet6Address) && ((address.getAddress()[0] & 0xfe) == 0xfc));
  }

  // Local network: Wi-Fi or Ethernet
  public boolean isOnLan() {
    return (findLanNetwork() != null);
  }

  // Only Wi-Fi, Ethernet and Cellular are supported
  public boolean isDeviceOnline() {
    return isOnLan() || (findNetwork(NetworkCapabilities.TRANSPORT_CELLULAR) != null);
  }

  // null: not found, system default to be used
  @Nullable
  NetworkInterface getLanNetworkInterface() {
    final LinkProperties linkProperties = getLanLinkProperties();
    final String interfaceName = (linkProperties == null) ? null : linkProperties.getInterfaceName();
    try {
      return (interfaceName == null) ? null : NetworkInterface.getByName(interfaceName);
    } catch (SocketException socketException) {
      return null;
    }
  }

  @Nullable
  String getLanIpAddress() {
    final LinkProperties linkProperties = getLanLinkProperties();
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

  @Nullable
  private LinkProperties getLanLinkProperties() {
    final Network lanNetwork = findLanNetwork();
    if (lanNetwork == null) {
      return null;
    }
    assert connectivityManager != null;
    return connectivityManager.getLinkProperties(lanNetwork);
  }

  // Wi-Fi preferred over Ethernet
  @Nullable
  private Network findLanNetwork() {
    final Network wifiNetwork = findNetwork(NetworkCapabilities.TRANSPORT_WIFI);
    return (wifiNetwork == null) ? findNetwork(NetworkCapabilities.TRANSPORT_ETHERNET) : wifiNetwork;
  }

  // getAllNetworks() is deprecated (API 31) in favor of NetworkCallback, which would require a
  // long-lived NetworkProxy; still functional and needed here for the VPN case
  @SuppressWarnings("deprecation")
  @Nullable
  private Network findNetwork(int networkCapability) {
    if (connectivityManager == null) {
      return null;
    }
    for (final Network network : connectivityManager.getAllNetworks()) {
      final NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
      if ((capabilities != null) &&
        capabilities.hasTransport(networkCapability) &&
        !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
        return network;
      }
    }
    return null;
  }

  @NonNull
  private String stripZoneId(@NonNull String address) {
    final int percent = address.indexOf('%');
    return (percent > 0) ? address.substring(0, percent) : address;
  }
}