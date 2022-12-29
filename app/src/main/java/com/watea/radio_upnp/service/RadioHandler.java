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
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.watea.radio_upnp.BuildConfig;
import com.watea.radio_upnp.model.Radio;
import com.watea.radio_upnp.model.RadioLibrary;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class RadioHandler extends AbstractHandler {
  private static final String LOG_TAG = RadioHandler.class.getName();
  private static final int METADATA_MAX = 256;
  private static final String PARAMS = "params";
  private static final String SEPARATOR = "_";
  private static final Pattern PATTERN_ICY = Pattern.compile(".*StreamTitle='([^;]*)';.*");
  @NonNull
  private final String userAgent;
  @NonNull
  private RadioLibrary.Provider radioLibraryProvider = unused -> null;
  @NonNull
  private Listener listener = new Listener() {
  };
  @NonNull
  private Controller controller = new Controller() {
  };

  public RadioHandler(@NonNull String userAgent) {
    super();
    this.userAgent = userAgent;
  }

  // Add ID and lock key to given URI as query parameter
  // Don't use several query parameters to avoid encoding troubles
  @NonNull
  public static Uri getHandledUri(@NonNull Uri uri, @NonNull Radio radio, @NonNull String lockKey) {
    return uri
      .buildUpon()
      .appendEncodedPath(RadioHandler.class.getSimpleName() + SEPARATOR + radio.getId())
      .appendQueryParameter(PARAMS, radio.getId() + SEPARATOR + lockKey)
      .build();
  }

  // Must be called
  public synchronized void setController(@NonNull Controller controller) {
    this.controller = controller;
  }

  public synchronized void resetController() {
    controller = new Controller() {
    };
  }

  public synchronized void unlock() {
    notifyAll();
  }

  @Override
  public void handle(
    String target,
    Request baseRequest,
    HttpServletRequest request,
    HttpServletResponse response) {
    // Valid request
    if ((baseRequest == null) || (request == null)) {
      Log.d(LOG_TAG, "Unexpected request received: request || baseRequest is null.");
      return;
    }
    final String param = request.getParameter(PARAMS);
    if (param == null) {
      Log.d(LOG_TAG, "Unexpected request received: param is null.");
      return;
    }
    // Request must contain a query with radio ID and lock key
    final String[] params = param.split(SEPARATOR);
    final String radioId = (params.length > 0) ? params[0] : null;
    final String lockKey = (params.length > 1) ? params[1] : null;
    if ((radioId == null) || (lockKey == null)) {
      Log.i(LOG_TAG, "Unexpected request received. Radio or UUID is null.");
    } else {
      final Radio radio = radioLibraryProvider.getFrom(Long.decode(radioId));
      if (radio == null) {
        Log.i(LOG_TAG, "Unknown radio");
      } else {
        baseRequest.setHandled(true);
        handleConnection(request, response, radio, lockKey);
      }
    }
  }

  public void bind(@NonNull Listener listener,
                   @NonNull RadioLibrary.Provider radioLibraryProvider) {
    this.listener = listener;
    this.radioLibraryProvider = radioLibraryProvider;
  }

  private void handleConnection(
    @NonNull final HttpServletRequest request,
    @NonNull final HttpServletResponse response,
    @NonNull final Radio radio,
    @NonNull final String lockKey) {
    final String method = request.getMethod();
    Log.d(LOG_TAG,
      "handleConnection: entering for " + method + " " + radio.getName() + "; " + lockKey);
    // For further use
    final boolean isGet = (method != null) && method.equals("GET");
    // Create WAN connection
    HttpURLConnection httpURLConnection = null;
    try (OutputStream outputStream = response.getOutputStream()) {
      // Accept M3U format
      httpURLConnection = new RadioURL(radio.getUrlFromM3u()).getActualHttpURLConnection(
        connection -> {
          // Default request method GET is used as some radio server handles HEAD too bad
          connection.setRequestProperty("User-Agent", userAgent);
          // ICY request
          if (isGet) {
            connection.setRequestProperty("Icy-Metadata", "1");
          }
        });
      Log.d(LOG_TAG, "Connected to radio URL");
      // Response to LAN
      for (String header : httpURLConnection.getHeaderFields().keySet()) {
        // ICY data not forwarded, as only used here
        if ((header != null) && !header.toLowerCase().startsWith("icy-")) {
          final String value = httpURLConnection.getHeaderField(header);
          if (value != null) {
            response.setHeader(header, value);
          }
        }
      }
      final String contentType = controller.getContentType();
      // contentType defined only for UPnP
      if (contentType.length() > 0) {
        // DLNA header, as found in documentation, not sure it is useful (should not)
        response.setHeader("contentFeatures.dlna.org", "*");
        response.setHeader("transferMode.dlna.org", "Streaming");
        // Force ContentType as some devices require it
        Log.d(LOG_TAG, "UPnP connection; ContentType forced: " + contentType);
        response.setContentType(contentType);
      }
      response.setStatus(HttpServletResponse.SC_OK);
      response.flushBuffer();
      Log.d(LOG_TAG, "Response sent to LAN client");
      if (isGet) {
        // Try to find charset
        final String contentEncoding = httpURLConnection.getContentEncoding();
        final Charset charset = (contentEncoding == null) ?
          Charset.defaultCharset() : Charset.forName(contentEncoding);
        // Find metadata place, 0 if undefined
        int metadataOffset = 0;
        final List<String> headerMeta = httpURLConnection.getHeaderFields().get("icy-metaint");
        try {
          metadataOffset = (headerMeta == null) ? 0 : Integer.parseInt(headerMeta.get(0));
        } catch (NumberFormatException numberFormatException) {
          Log.w(LOG_TAG, "Malformed header icy-metaint");
        }
        if (metadataOffset > 0) {
          Log.i(LOG_TAG, "Metadata expected at index: " + metadataOffset);
        } else if (metadataOffset == 0) {
          Log.i(LOG_TAG, "No metadata expected");
        } else {
          metadataOffset = 0;
          Log.w(LOG_TAG, "Wrong metadata value");
        }
        handleStreaming(
          httpURLConnection.getInputStream(),
          charset.newDecoder(),
          metadataOffset,
          outputStream,
          httpURLConnection.getHeaderField("icy-br"),
          lockKey);
      }
    } catch (Exception exception) {
      Log.d(LOG_TAG, "handleConnection error", exception);
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
    @NonNull final InputStream inputStream,
    @NonNull final CharsetDecoder charsetDecoder,
    final int metadataOffset,
    @NonNull final OutputStream outputStream,
    @Nullable final String rate,
    @NonNull final String lockKey) throws IOException {
    Log.d(LOG_TAG, "handleStreaming: entering");
    // Flush information, send rate
    listener.onNewInformation("", rate, lockKey);
    final byte[] buffer = new byte[1];
    final ByteBuffer metadataBuffer = ByteBuffer.allocate(METADATA_MAX);
    int metadataBlockBytesRead = 0;
    int metadataSize = 0;
    // Stop if not current controller
    while (lockKey.equals(controller.getKey()) && (inputStream.read(buffer) > 0)) {
      final boolean isActiv = !controller.isPaused();
      // Only stream data are transferred
      if ((metadataOffset == 0) || (++metadataBlockBytesRead <= metadataOffset)) {
        if (isActiv) {
          outputStream.write(buffer);
        }
      } else {
        // Metadata: look for title information
        final int metadataIndex = metadataBlockBytesRead - metadataOffset - 1;
        // First byte gives size (16 bytes chunks) to read for metadata
        if (metadataIndex == 0) {
          metadataSize = buffer[0] * 16;
          metadataBuffer.clear();
        } else if (metadataIndex <= METADATA_MAX) {
          // Other bytes are metadata
          metadataBuffer.put(buffer[0]);
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
            final Matcher matcher = PATTERN_ICY.matcher(metadata);
            // Tell listener
            final String information =
              (matcher.find() && (matcher.groupCount() > 0)) ? matcher.group(1) : null;
            if (isActiv && (information != null)) {
              listener.onNewInformation(information, rate, lockKey);
            }
          }
          metadataBlockBytesRead = 0;
        }
      }
    }
    Log.d(LOG_TAG, "handleStreaming: leaving");
  }

  public interface Listener {
    default void onNewInformation(
      @NonNull String information,
      @Nullable String rate,
      @NonNull String lockKey) {
    }
  }

  public interface Controller {
    @NonNull
    default String getKey() {
      return "";
    }

    default boolean isPaused() {
      return false;
    }

    // Only for UPnP
    @NonNull
    default String getContentType() {
      return "";
    }
  }
}