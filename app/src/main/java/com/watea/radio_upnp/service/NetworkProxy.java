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
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.net.Inet4Address;
import java.net.InetAddress;

public class NetworkProxy {
  private static final String SCHEME = "http";
  @Nullable
  private final ConnectivityManager connectivityManager;

  // Shall be called after onCreate
  public NetworkProxy(@NonNull Context context) {
    connectivityManager =
      (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
  }

  @NonNull
  private static Uri getUri(@NonNull String address, int port) {
    return new Uri.Builder()
      .scheme(SCHEME)
      .appendEncodedPath("/" + address + ":" + port)
      .build();
  }

  @NonNull
  public static Uri getLoopbackUri(int port) {
    return getUri("127.0.0.1", port);
  }

  public boolean isOnWifi() {
    return isOnNetworkCapability(NetworkCapabilities.TRANSPORT_WIFI);
  }

  // Only Wifi and Cellular is supported
  public boolean isDeviceOnline() {
    return isOnWifi() || isOnNetworkCapability(NetworkCapabilities.TRANSPORT_CELLULAR);
  }

  @Nullable
  public Uri getUri(int port) {
    final String ipAddress = getWifiIpAddress();
    return (ipAddress == null) ? null : getUri(ipAddress, port);
  }

  @Nullable
  public String getWifiIpAddress() {
    if (connectivityManager == null) {
      return null;
    }
    final Network network = connectivityManager.getActiveNetwork();
    if (network == null) {
      return null;
    }
    final NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(network);
    if ((caps == null) || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
      return null;
    }
    final LinkProperties props = connectivityManager.getLinkProperties(network);
    if (props == null) {
      return null;
    }
    for (LinkAddress linkAddress : props.getLinkAddresses()) {
      final InetAddress inetAddress = linkAddress.getAddress();
      if ((inetAddress instanceof Inet4Address) && !inetAddress.isLoopbackAddress()) {
        return inetAddress.getHostAddress();
      }
    }
    return null;
  }

  public boolean isOnNetworkCapability(int networkCapability) {
    if (connectivityManager == null) {
      return false;
    } else {
      final NetworkCapabilities capabilities =
        connectivityManager.getNetworkCapabilities(connectivityManager.getActiveNetwork());
      return (capabilities != null) && capabilities.hasTransport(networkCapability);
    }
  }
}