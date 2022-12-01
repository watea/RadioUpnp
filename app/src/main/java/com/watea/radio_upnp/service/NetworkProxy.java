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
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;

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

  // Only Wifi and Cellular is supported
  public boolean isDeviceOffline() {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
      if (connectivityManager == null) {
        return true;
      } else {
        final NetworkCapabilities capabilities =
          connectivityManager.getNetworkCapabilities(connectivityManager.getActiveNetwork());
        return (capabilities == null) ||
          !(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI));
      }
    }
    else
    {
      NetworkInfo netInfo = connectivityManager.getActiveNetworkInfo();
      return (netInfo == null) ||
        !(netInfo.getType() == ConnectivityManager.TYPE_MOBILE ||
          netInfo.getType() == ConnectivityManager.TYPE_WIFI);
    }
  }

  public boolean hasWifiIpAddress() {
    return (getIpAddress() != null);
  }

  @Nullable
  public Uri getUri(int port) {
    final String ipAddress = getIpAddress();
    return (ipAddress == null) ? null : getUri(ipAddress, port);
  }

  @Nullable
  private String getIpAddress() {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
      if (connectivityManager != null) {
        final LinkProperties linkProperties =
          connectivityManager.getLinkProperties(connectivityManager.getActiveNetwork());
        if (linkProperties != null) {
          for (LinkAddress linkAddress : linkProperties.getLinkAddresses()) {
            final InetAddress inetAddress = linkAddress.getAddress();
              if (inetAddress instanceof java.net.Inet4Address) {
                return inetAddress.getHostAddress();
              }
            }
          }
        }
      } else {
        try {
          List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
          for (NetworkInterface intf : interfaces) {
            List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
            for (InetAddress inetAddress : addrs) {
              if (!inetAddress.isLoopbackAddress()) {
                if (inetAddress instanceof java.net.Inet4Address)
                   return inetAddress.getHostAddress();
              }
            }
          }
        } catch (Exception ignored) {
      } // eat exceptions
    }
    return null;
  }
}