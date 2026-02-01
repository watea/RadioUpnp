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
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.watea.candidhttpserver.HttpServer;
import com.watea.radio_upnp.BuildConfig;
import com.watea.radio_upnp.R;
import com.watea.radio_upnp.model.Radio;
import com.watea.radio_upnp.model.Radios;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URISyntaxException;
import java.net.URLConnection;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RadioHandler implements HttpServer.Handler {
  private static final String LOG_TAG = RadioHandler.class.getSimpleName();
  private static final int METADATA_MAX = 256;
  private static final String KEY = "key";
  private static final Pattern PATTERN_ICY = Pattern.compile(".*StreamTitle='([^;]*)';.*");
  @NonNull
  private final String userAgent;
  @NonNull
  private final Listener listener;
  @NonNull
  private final Controller controller;

  public RadioHandler(@NonNull Context context, @NonNull Listener listener, @NonNull Controller controller) {
    this.userAgent = context.getString(R.string.app_name);
    this.listener = listener;
    this.controller = controller;
  }

  // Add ID and lock key to given URI as query parameter
  @NonNull
  public static Uri getHandledUri(@NonNull Uri uri, @NonNull Radio radio, @NonNull String lockKey) {
    return uri
      .buildUpon()
      .appendEncodedPath(radio.getId())
      .appendQueryParameter(KEY, lockKey)
      .build();
  }

  @Override
  public void handle(
    @NonNull HttpServer.Request request,
    @NonNull HttpServer.Response response,
    @NonNull OutputStream responseStream) throws IOException {
    Log.d(LOG_TAG, "handle: entering");
    final String method = request.getMethod();
    final String path = request.getPath();
    // Request must contain a query with radio ID and lock key
    final String lockKey = request.getParams(KEY);
    if (lockKey == null) {
      Log.d(LOG_TAG, "handle: leaving, unexpected request received: lockKey is null");
      return;
    }
    final Radio radio = Radios.getInstance().getRadioFromId(path.replace("/", ""));
    if (radio == null) {
      Log.d(LOG_TAG, "handle: leaving, unknown radio");
      return;
    }
    final boolean isGet = (method.equals("GET"));
    // Create WAN connection
    final HttpURLConnection httpURLConnection =
      new RadioURL(radio.getURL()).getActualHttpURLConnection(this::setHeader);
    Log.d(LOG_TAG, "Connected to radio " + (isGet ? "GET: " : "HEAD: ") + radio.getName());
    final boolean isHls = HlsHandler.isHls(httpURLConnection);
    // Reset information
    listener.onNewInformation("", lockKey);
    // Reset rate
    final String directRate = httpURLConnection.getHeaderField("icy-br");
    listener.onNewRate((isHls || (directRate == null)) ? "" : directRate, lockKey);
    // Build hlsHandler
    final HlsHandler hlsHandler;
    try {
      hlsHandler = isHls ?
        new HlsHandler(
          httpURLConnection,
          this::setHeader,
          rate ->
            listener.onNewRate((rate == null) ? "" : rate.substring(0, rate.length() - 3), lockKey))
        : null;
    } catch (URISyntaxException uRISyntaxException) {
      Log.e(LOG_TAG, "handle: hlsHandler failed to be be created", uRISyntaxException);
      return;
    }
    try (final InputStream inputStream =
           isHls ? hlsHandler.getInputStream() : httpURLConnection.getInputStream()) {
      final ConnectionHandler connectionHandler = isHls ?
        new ConnectionHandler(httpURLConnection, inputStream, lockKey) {
          @Override
          @NonNull
          protected Charset getCharset() {
            return Charset.defaultCharset();
          }

          // ICY data are not processed
          @Override
          protected int getMetadataOffset() {
            return 0;
          }
        } :
        new ConnectionHandler(httpURLConnection, inputStream, lockKey);
      // Build response
      String contentType = controller.getContentType();
      // Force ContentType as some UPnP devices require it
      if (contentType.isEmpty()) {
        contentType = httpURLConnection.getContentType();
      }
      // DLNA header, as found in documentation, not sure it is useful (should not)
      response.addHeader(HttpServer.Response.CONTENT_TYPE, contentType);
      response.addHeader("contentFeatures.dlna.org", "*");
      response.addHeader("transferMode.dlna.org", "Streaming");
      response.send();
      if (isGet) {
        connectionHandler.handle(responseStream);
      }
      Log.d(LOG_TAG, "handle: leaving with response");
    } catch (URISyntaxException uRISyntaxException) {
      Log.e(LOG_TAG, "handle: failed to get input stream", uRISyntaxException);
    }
  }

  private void setHeader(@NonNull URLConnection urlConnection) {
    // Default request method GET is used as some radio server handles HEAD too bad
    urlConnection.setRequestProperty("User-Agent", userAgent);
    // ICY request in any case even if not used
    urlConnection.setRequestProperty("Icy-Metadata", "1");
  }

  public interface Listener {
    default void onNewInformation(@NonNull String information, @NonNull String lockKey) {
    }

    default void onNewRate(@Nullable String rate, @NonNull String lockKey) {
    }
  }

  public interface Controller {
    @NonNull
    String getKey();

    // Empty if unknown
    @NonNull
    String getContentType();

    boolean isActiv();
  }

  private class ConnectionHandler {
    @NonNull
    final HttpURLConnection httpURLConnection;
    @NonNull
    final InputStream inputStream;
    @NonNull
    final String lockKey;

    private ConnectionHandler(
      @NonNull HttpURLConnection httpURLConnection,
      @NonNull InputStream inputStream,
      @NonNull String lockKey) {
      this.httpURLConnection = httpURLConnection;
      this.inputStream = inputStream;
      this.lockKey = lockKey;
    }

    public void handle(@NonNull OutputStream outputStream) throws IOException {
      final byte[] buffer = new byte[1];
      final CharsetDecoder charsetDecoder = getCharset().newDecoder();
      final int metadataOffset = getMetadataOffset();
      final ByteBuffer metadataBuffer = ByteBuffer.allocate(METADATA_MAX);
      int metadataBlockBytesRead = 0;
      int metadataSize = 0;
      while (controller.isActiv() && lockKey.equals(controller.getKey()) && (inputStream.read(buffer) > 0)) {
        // Only stream data are transferred
        if ((metadataOffset == 0) || (++metadataBlockBytesRead <= metadataOffset)) {
          outputStream.write(buffer[0]);
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
              if (information != null) {
                listener.onNewInformation(information, lockKey);
              }
            }
            metadataBlockBytesRead = 0;
          }
        }
      }
    }

    @NonNull
    protected Charset getCharset() {
      // Try to find charset
      final String contentEncoding = httpURLConnection.getContentEncoding();
      return (contentEncoding == null) ?
        Charset.defaultCharset() : Charset.forName(contentEncoding);
    }

    protected int getMetadataOffset() {
      // Try to find metadataOffset
      final int metadataOffset;
      final List<String> headerMeta = httpURLConnection.getHeaderFields().get("icy-metaint");
      try {
        metadataOffset = (headerMeta == null) ? 0 : Integer.parseInt(headerMeta.get(0));
      } catch (NumberFormatException numberFormatException) {
        Log.w(LOG_TAG, "Malformed header icy-metaint, no metadata expected");
        return 0;
      }
      if (metadataOffset > 0) {
        Log.d(LOG_TAG, "Metadata expected at index: " + metadataOffset);
      } else if (metadataOffset == 0) {
        Log.d(LOG_TAG, "No metadata expected");
      } else {
        Log.w(LOG_TAG, "Wrong metadata value");
      }
      return Math.max(metadataOffset, 0);
    }
  }
}