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

import static java.nio.charset.StandardCharsets.UTF_8;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Vector;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SsdpClient {
  public static final String DEVICE = "urn:schemas-upnp-org:device:MediaRenderer:";
  public static final String AV_TRANSPORT_SERVICE_ID = "AVTransport";
  private static final String DEVICE_VERSION = "1";
  private static final String LOG_TAG = SsdpClient.class.getSimpleName();
  private static final String MULTICAST_ADDRESS = "239.255.255.250";
  private static final String WLAN = "wlan0";
  private static final int SSDP_PORT = 1900;
  private static final int SEARCH_DELAY = 500; // ms
  private static final int SEARCH_REPEAT = 3;
  private static final int MX = 3; // s
  private static final int SEARCH_TTL = 2; // UPnP spec
  private static final int MARGIN = 100; // ms
  private static final String SEARCH_MESSAGE =
    "M-SEARCH * HTTP/1.1\r\n" +
      "HOST: 239.255.255.250:1900\r\n" +
      "MAN: \"ssdp:discover\"\r\n" +
      "MX: " + MX + "\r\n" +
      "ST: " + DEVICE + DEVICE_VERSION + "\r\n\r\n";
  private static final Pattern CACHE_CONTROL_PATTERN = Pattern.compile("max-age *= *([0-9]+).*");
  // Date format for expires headers
  private static final SimpleDateFormat DATE_HEADER_FORMAT = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
  private static final Pattern SEARCH_REQUEST_LINE_PATTERN = Pattern.compile("^HTTP/1\\.1 [0-9]+ .*");
  private static final Pattern SERVICE_ANNOUNCEMENT_LINE_PATTERN = Pattern.compile("NOTIFY \\* HTTP/1\\.1");
  private static final Pattern HEADER_PATTERN = Pattern.compile("(.*?):(.*)$");
  // CRLF
  private static final String S_CRLF = "\r\n";
  private static final String CACHE_CONTROL = "CACHE-CONTROL";
  private static final String EXPIRES = "EXPIRES";
  private static final byte[] B_CRLF = S_CRLF.getBytes(UTF_8);
  private final Listener listener;
  // Cache
  private final List<SsdpService> ssdpServices = new Vector<>(); // Threadsafe List implementation
  private boolean isRunning;
  @Nullable
  private MulticastSocket searchSocket = null; // Multicast socket used here only for TTL, otherwise DatagramSocket could be enough
  @Nullable
  private MulticastSocket listenSocket = null;
  @Nullable
  private NetworkInterface networkInterface = null;

  public SsdpClient(@NonNull Listener listener) {
    this.listener = listener;
  }

  // Network shall be available (implementation dependant)
  public void start() {
    Log.d(LOG_TAG, "start: entering");
    try {
      // Search socket and timeout
      searchSocket = new MulticastSocket();
      searchSocket.setSoTimeout(MX * 1000 + SEARCH_DELAY * SEARCH_REPEAT + MARGIN);
      searchSocket.setTimeToLive(SEARCH_TTL);
      // Late binding in case port is already used
      listenSocket = new MulticastSocket(null);
      listenSocket.setReuseAddress(true);
      listenSocket.bind(new InetSocketAddress(SSDP_PORT));
      // Join the multicast group on the specified network interface (wlan0 for Wi-Fi)
      networkInterface = NetworkInterface.getByName(WLAN);
      listenSocket.joinGroup(new InetSocketAddress(MULTICAST_ADDRESS, SSDP_PORT), networkInterface);
      // Receive on both ports unicast and multicast responses
      isRunning = true;
      ssdpServices.clear();
      new Thread(() -> receive(searchSocket)).start();
      new Thread(() -> receive(listenSocket)).start();
      // Now we can search
      final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
      for (int i = 0; i < SEARCH_REPEAT; i++) {
        scheduler.schedule(this::search, i * SEARCH_DELAY, TimeUnit.MILLISECONDS);
      }
      scheduler.shutdown();
    } catch (Exception exception) {
      Log.e(LOG_TAG, "start: failed!", exception);
      stop();
      listener.onFatalError();
    }
  }

  public void stop() {
    Log.d(LOG_TAG, "stop: entering");
    isRunning = false;
    if (searchSocket != null) {
      searchSocket.close();
    }
    if (listenSocket != null) {
      if (networkInterface != null) {
        try {
          listenSocket.leaveGroup(new InetSocketAddress(MULTICAST_ADDRESS, SSDP_PORT), networkInterface);
        } catch (IOException iOException) {
          Log.e(LOG_TAG, "stop: unable to leave group!", iOException);
        }
      }
      listenSocket.close();
    }
    listener.onStop();
  }

  public void search() {
    if ((searchSocket != null) && !searchSocket.isClosed()) {
      try {
        final byte[] sendData = SEARCH_MESSAGE.getBytes();
        final DatagramPacket packet = new DatagramPacket(sendData, sendData.length, InetAddress.getByName(MULTICAST_ADDRESS), SSDP_PORT);
        searchSocket.send(packet);
      } catch (IOException iOException) {
        Log.e(LOG_TAG, "SSDP search failed!", iOException);
      }
    }
  }

  private void receive(@NonNull DatagramSocket datagramSocket) {
    final byte[] receiveData = new byte[1024];
    final DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
    while (isRunning) {
      try {
        datagramSocket.receive(receivePacket);
        final SsdpResponse ssdpResponse = parse(receivePacket);
        if (ssdpResponse == null) {
          Log.e(LOG_TAG, "receive: unable to parse response");
        } else {
          final SsdpService ssdpService = new SsdpService(ssdpResponse);
          if (ssdpService.getType() == SsdpResponse.Type.DISCOVERY_RESPONSE) {
            // Handle cache
            final int index = ssdpServices.indexOf(ssdpService);
            boolean isNew = false;
            if (index < 0) {
              ssdpServices.add(ssdpService);
              isNew = true;
            } else if (ssdpServices.get(index).isExpired()) {
              ssdpServices.set(index, ssdpService);
              isNew = true;
            }
            if (isNew) {
              listener.onServiceDiscovered(ssdpService);
            }
          } else {
            listener.onServiceAnnouncement(ssdpService);
          }
        }
      } catch (IOException iOException) {
        if (iOException instanceof SocketTimeoutException) {
          Log.d(LOG_TAG, "receive: timeout");
        } else {
          Log.e(LOG_TAG, "receive:", iOException);
        }
      }
    }
  }

  @Nullable
  private SsdpResponse parse(@NonNull DatagramPacket datagramPacket) {
    final byte[] data = datagramPacket.getData();
    // Find position of the last header data
    int endOfHeaders = findEndOfHeaders(data);
    if (endOfHeaders == -1) {
      endOfHeaders = datagramPacket.getLength();
    }
    // Retrieve all header lines
    final List<String> headerLines = Arrays.asList(new String(Arrays.copyOfRange(data, 0, endOfHeaders)).split(S_CRLF));
    final String firstLine = headerLines.get(0);
    // Determine type of message
    final SsdpResponse.Type type =
      SEARCH_REQUEST_LINE_PATTERN.matcher(firstLine).matches() ?
        SsdpResponse.Type.DISCOVERY_RESPONSE :
        SERVICE_ANNOUNCEMENT_LINE_PATTERN.matcher(firstLine).matches() ?
          SsdpResponse.Type.PRESENCE_ANNOUNCEMENT :
          null;
    if (type == null) {
      return null;
    }
    // Let's parse our headers
    final Map<String, String> headers = new HashMap<>();
    headerLines.stream().map(HEADER_PATTERN::matcher).filter(Matcher::matches).forEach(matcher -> {
      final String key = matcher.group(1);
      final String value = matcher.group(2);
      if ((key != null) && (value != null)) {
        headers.put(key.toUpperCase().trim(), value.trim());
      }
    });
    // Determine expiry depending on the presence of cache-control or expires headers
    final long expiry = parseCacheHeader(headers);
    // Let's see if we have a body.
    // If we do, let's copy the byte array and put it into the response for the user to get.
    final int endOfBody = datagramPacket.getLength();
    final byte[] body = (endOfBody > endOfHeaders + 4) ? Arrays.copyOfRange(data, endOfHeaders + 4, endOfBody) : null;
    return new SsdpResponse(type, headers, body, expiry, datagramPacket.getAddress());
  }

  // Parse both Cache-Control and Expires headers to determine if there is any caching strategy requested by service
  private long parseCacheHeader(@NonNull Map<String, String> headers) {
    final String cacheControlHeader = headers.get(CACHE_CONTROL);
    if (cacheControlHeader != null) {
      final Matcher m = CACHE_CONTROL_PATTERN.matcher(cacheControlHeader);
      if (m.matches()) {
        return new Date().getTime() + Long.parseLong(m.group(1)) * 1000L;
      }
    }
    final String expires = headers.get(EXPIRES);
    if (expires != null) {
      try {
        final Date date = DATE_HEADER_FORMAT.parse(expires);
        return (date == null) ? 0 : date.getTime();
      } catch (ParseException parseException) {
        Log.d(LOG_TAG, "parseCacheHeader: failed to parse expires header");
      }
    }
    // No result, no expiry strategy
    return 0;
  }

  // Find the index matching the end of the header data
  private int findEndOfHeaders(@NonNull byte[] data) {
    for (int i = 0; i < data.length - 3; i++) {
      if (data[i] != B_CRLF[0] || data[i + 1] != B_CRLF[1] || data[i + 2] != B_CRLF[0] || data[i + 3] != B_CRLF[1]) {
        continue;
      }
      // Headers finish here
      return i;
    }
    return -1;
  }

  public boolean isStarted() {
    return isRunning;
  }

  public interface Listener {
    void onServiceDiscovered(@NonNull SsdpService service);

    void onServiceAnnouncement(@NonNull SsdpService service);

    void onFatalError();

    void onStop();
  }
}