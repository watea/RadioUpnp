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

import static android.content.Context.WIFI_SERVICE;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteOrder;

public class NetworkProxy {
  private static final String LOG_TAG = NetworkProxy.class.getName();
  private static final String SCHEME = "http";
  @NonNull
  private final Context context;

  public NetworkProxy(@NonNull Context context) {
    this.context = context;
  }

  @Nullable
  private static String ipAddressToString(int ipAddress) {
    try {
      return InetAddress.getByAddress(
        BigInteger.valueOf(ByteOrder.nativeOrder().equals(ByteOrder.LITTLE_ENDIAN) ?
          Integer.reverseBytes(ipAddress) : ipAddress).toByteArray()).getHostAddress();
    } catch (UnknownHostException unknownHostException) {
      Log.e(LOG_TAG, "Error decoding IP address", unknownHostException);
    }
    return null;
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

  public boolean isDeviceOffline() {
    boolean result = true;
    try {
      ConnectivityManager connectivityManager
        = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
      NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
      result = (activeNetworkInfo == null) || !activeNetworkInfo.isConnected();
    } catch (Exception exception) {
      Log.e(LOG_TAG, "Error testing ConnectivityManager", exception);
    }
    return result;
  }

  public boolean hasWifiIpAddress() {
    return (getIpAddress() != null);
  }

  @Nullable
  public Uri getUri(int port) {
    String ipAddress = getIpAddress();
    return (ipAddress == null) ? null : getUri(ipAddress, port);
  }

  @Nullable
  private String getIpAddress() {
    String result = null;
    try {
      WifiManager wifiManager =
        (WifiManager) context.getApplicationContext().getSystemService(WIFI_SERVICE);
      result = ipAddressToString(wifiManager.getConnectionInfo().getIpAddress());
    } catch (Exception exception) {
      Log.e(LOG_TAG, "Error getting IP address", exception);
    }
    return result;
  }
}