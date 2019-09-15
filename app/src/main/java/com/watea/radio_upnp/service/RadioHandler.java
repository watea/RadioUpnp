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

import android.net.Uri;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.util.Log;

import com.watea.radio_upnp.BuildConfig;
import com.watea.radio_upnp.model.Radio;
import com.watea.radio_upnp.model.RadioLibrary;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class RadioHandler extends AbstractHandler {
  private static final String LOG_TAG = RadioHandler.class.getName();
  private static final int CONNECT_TIMEOUT = 5000;
  private static final int READ_TIMEOUT = 10000;
  private static final int METADATA_MAX = 256;
  private static final String GET = "GET";
  private static final String HEAD = "HEAD";
  private static final String USER_AGENT = "User-Agent";
  private static final String PARAMS = "params";
  @SuppressWarnings("RegExpEmptyAlternationBranch")
  private static final String SEPARATOR = "|";
  // For ICY metadata
  private static final Pattern PATTERN = Pattern.compile(".*StreamTitle='([^;]*)';.*");
  @NonNull
  private final String userAgent;
  @NonNull
  private final RadioLibrary radioLibrary;
  private final boolean isBuffered;
  @NonNull
  private final Handler handler = new Handler();
  @NonNull
  private final Map<String, String> remoteUserAgents = new Hashtable<>();
  @NonNull
  private Listener listener;


  // Has always a listener
  RadioHandler(
    @NonNull String userAgent,
    @NonNull RadioLibrary radioLibrary,
    boolean isBuffered) {
    super();
    this.userAgent = userAgent;
    this.radioLibrary = radioLibrary;
    this.isBuffered = isBuffered;
    // Dummy listener
    this.listener = new Listener() {
      @Override
      public void onNewInformation(
        @NonNull Radio radio, @NonNull String information, @NonNull String lockKey) {
      }

      @Override
      public void onError(@NonNull Radio radio, @NonNull String lockKey) {
      }

      @NonNull
      @Override
      public String getLockKey() {
        return VOID;
      }
    };
  }

  // Add ID and lock key to given URI as query parameter
  @NonNull
  public static Uri getHandledUri(
    @NonNull Uri uri, @NonNull Radio radio, @NonNull String lockKey) {
    return uri
      .buildUpon()
      .appendEncodedPath("radio")
      // Add radio ID + lock key as query parameter
      // Don't use several query parameters to avoid encoding troubles
      .appendQueryParameter(PARAMS, radio.getId() + SEPARATOR + lockKey)
      .build();
  }

  public void setListener(@NonNull Listener listener) {
    this.listener = listener;
  }

  @Override
  public void handle(
    String target,
    Request baseRequest,
    HttpServletRequest request,
    HttpServletResponse response) {
    // Request must contain a query with radio ID and lock key
    String[] params = request.getParameter(PARAMS).split("\\" + SEPARATOR);
    String radioId = (params.length > 0) ? params[0] : null;
    String lockKey = (params.length > 1) ? params[1] : null;
    if ((radioId == null) || (lockKey == null) || lockKey.equals(Listener.VOID)) {
      Log.d(LOG_TAG, "Unexpected request received. Radio/UUID: " + radioId + "/" + lockKey);
    } else {
      Radio radio = radioLibrary.getFrom(Long.decode(radioId));
      if (radio == null) {
        Log.d(LOG_TAG, "Unknown radio");
      } else {
        baseRequest.setHandled(true);
        handleConnection(request, response, radio, lockKey);
      }
    }
  }

  private void handleConnection(
    @NonNull HttpServletRequest request,
    @NonNull HttpServletResponse response,
    @NonNull final Radio radio,
    @NonNull final String lockKey) {
    String method = request.getMethod();
    Log.d(LOG_TAG,
      "handleConnection: entering for " + method + " " + radio.getName() + "; " + lockKey);
    // For further user
    boolean isGet = GET.equals(method);
    final String remoteUserAgent = request.getHeader(USER_AGENT);
    if (remoteUserAgent != null) {
      remoteUserAgents.put(lockKey, remoteUserAgent);
    }
    // Create WAN connection
    HttpURLConnection httpURLConnection = null;
    try (OutputStream outputStream = isBuffered ?
      new BufferedOutputStream(response.getOutputStream()) : response.getOutputStream()) {
      httpURLConnection = (HttpURLConnection) radio.getURL().openConnection();
      httpURLConnection.setInstanceFollowRedirects(true);
      httpURLConnection.setConnectTimeout(CONNECT_TIMEOUT);
      httpURLConnection.setReadTimeout(READ_TIMEOUT);
      httpURLConnection.setRequestMethod(isGet ? GET : HEAD);
      httpURLConnection.setRequestProperty(USER_AGENT, userAgent);
      if (isGet) {
        httpURLConnection.setRequestProperty("Icy-metadata", "1");
      }
      httpURLConnection.connect();
      Log.d(LOG_TAG, "handleConnection: connected to radio URL");
      // Response to LAN
      String contentType = httpURLConnection.getContentType();
      response.setContentType((contentType == null) ? "audio/mpeg" : contentType);
      response.setHeader("Server", userAgent);
      response.setHeader("Accept-Ranges", "bytes");
      response.setHeader("Cache-Control", "no-cache");
      response.setHeader("Connection", "close");
      response.setStatus(HttpServletResponse.SC_OK);
      response.flushBuffer();
      Log.d(LOG_TAG, "handleConnection: response sent to LAN client");
      if (isGet) {
        // Try to find charset
        String contentEncoding = httpURLConnection.getContentEncoding();
        Charset charset = (contentEncoding == null) ?
          Charset.defaultCharset() : Charset.forName(contentEncoding);
        // Find metadata place, 0 if undefined
        int metadataOffset = 0;
        List<String> headerMeta = httpURLConnection.getHeaderFields().get("icy-metaint");
        try {
          metadataOffset = (headerMeta == null) ? 0 : Integer.parseInt(headerMeta.get(0));
        } catch (NumberFormatException numberFormatException) {
          Log.w(LOG_TAG, "Malformed header icy-metaint");
        }
        if (metadataOffset > 0) {
          Log.d(LOG_TAG, "Metadata expected at index: " + metadataOffset);
        } else if (metadataOffset == 0) {
          Log.d(LOG_TAG, "No metadata expected");
        } else {
          metadataOffset = 0;
          Log.w(LOG_TAG, "Wrong metadata value");
        }
        handleStreaming(
          httpURLConnection.getInputStream(),
          charset.newDecoder(),
          metadataOffset,
          outputStream,
          radio,
          lockKey);
      }
    } catch (IOException iOException) {
      Log.d(LOG_TAG, "IOException", iOException);
      // Throw error only if stream is last requested.
      // Used in case client request the stream several times before actually playing.
      // As seen with BubbleUPnP.
      handler.postDelayed(
        new Runnable() {
          @Override
          public void run() {
            if ((remoteUserAgent == null) ||
              remoteUserAgent.equals(remoteUserAgents.get(lockKey))) {
              Log.d(LOG_TAG, "Error sent to listener");
              listener.onError(radio, lockKey);
            }
          }
        },
        500);
    } finally {
      if (httpURLConnection != null) {
        httpURLConnection.disconnect();
      }
    }
    Log.d(LOG_TAG, "handleConnection: leaving");
  }

  // Forward stream data and handle metadata
  // metadataOffset = 0 if no metadata
  private void handleStreaming(
    @NonNull InputStream inputStream,
    @NonNull CharsetDecoder charsetDecoder,
    int metadataOffset,
    @NonNull OutputStream outputStream,
    @NonNull final Radio radio,
    @NonNull final String lockKey) throws IOException {
    Log.d(LOG_TAG, "handleStreaming: entering");
    final byte[] buffer = new byte[1];
    final ByteBuffer metadataBuffer = ByteBuffer.allocate(METADATA_MAX);
    int metadataBlockBytesRead = 0;
    int metadataSize = 0;
    while (inputStream.read(buffer) != -1) {
      // Stop if not a valid listener
      if (listener.getLockKey().equals(lockKey)) {
        // Only stream data are transferred
        if ((metadataOffset == 0) || (++metadataBlockBytesRead <= metadataOffset)) {
          outputStream.write(buffer);
        } else {
          // Metadata: look for title information
          int metadataIndex = metadataBlockBytesRead - metadataOffset - 1;
          // First byte gives size (16 bytes chunks) to read for metadata
          if (metadataIndex == 0) {
            metadataSize = buffer[0] * 16;
            metadataBuffer.clear();
          } else {
            // Other bytes are metadata
            if (metadataIndex <= METADATA_MAX) {
              metadataBuffer.put(buffer[0]);
            }
          }
          // End of metadata, extract pattern
          if (metadataIndex == metadataSize) {
            CharBuffer metadata = null;
            metadataBuffer.flip();
            // Exception blocked on metadata
            try {
              metadata = charsetDecoder.decode(metadataBuffer);
            } catch (Exception exception) {
              if (BuildConfig.DEBUG) {
                Log.w(LOG_TAG, "Error decoding metadata", exception);
              }
            }
            if ((metadata != null) && (metadata.length() > 0)) {
              if (BuildConfig.DEBUG) {
                Log.d(LOG_TAG, "Size|Metadata: " + metadataSize + "|" + metadata);
              }
              final Matcher matcher = PATTERN.matcher(metadata);
              // Tell listener
              if (matcher.find()) {
                handler.post(new Runnable() {
                  @Override
                  public void run() {
                    listener.onNewInformation(radio, matcher.group(1), lockKey);
                  }
                });
              }
            }
            metadataBlockBytesRead = 0;
          }
        }
      } else {
        Log.d(LOG_TAG, "handleStreaming: requested to stop");
        break;
      }
    }
    Log.d(LOG_TAG, "handleStreaming: leaving");
  }

  public interface Listener {
    String VOID = "";

    void onNewInformation(
      @NonNull Radio radio, @NonNull String information, @NonNull String lockKey);

    void onError(@NonNull Radio radio, @NonNull String lockKey);

    @NonNull
    String getLockKey();
  }
}