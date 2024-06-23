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
import com.watea.radio_upnp.activity.MainActivity;
import com.watea.radio_upnp.model.Radio;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URLConnection;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import fi.iki.elonen.NanoHTTPD;

public class RadioHandler implements NanoHttpServer.Handler {
  private static final String LOG_TAG = RadioHandler.class.getName();
  private static final int METADATA_MAX = 256;
  private static final String PARAMS = "params";
  private static final String KEY = "key";
  private static final String RADIO_ID = "radio_id";
  private static final String SEPARATOR = "_";
  private static final Controller DEFAULT_CONTROLLER = new Controller() {
  };
  private static final Pattern PATTERN_ICY = Pattern.compile(".*StreamTitle='([^;]*)';.*");
  @NonNull
  private final String userAgent;
  @NonNull
  private final Listener listener;
  @NonNull
  private Controller controller = DEFAULT_CONTROLLER;
  @Nullable
  private HlsHandler hlsHandler;

  public RadioHandler(@NonNull String userAgent, @NonNull Listener listener) {
    this.userAgent = userAgent;
    this.listener = listener;
  }

  // Add ID and lock key to given URI as query parameter.
  // Don't use several query parameters to avoid encoding troubles.
  // TODO: à revoir
  @NonNull
  public static Uri getHandledUri(@NonNull Uri uri, @NonNull Radio radio, @NonNull String lockKey) {
    return uri
      .buildUpon()
      .appendEncodedPath(RadioHandler.class.getSimpleName() + SEPARATOR + radio.hashCode())
      .appendQueryParameter(PARAMS, radio.getId() + SEPARATOR + lockKey)
      .build();
  }

  // Must be called
  public synchronized void setController(@NonNull Controller controller) {
    this.controller = controller;
  }

  public synchronized void resetController() {
    controller = DEFAULT_CONTROLLER;
  }

  @Override
  public NanoHTTPD.Response handle(@NonNull NanoHTTPD.IHTTPSession iHTTPSession) {
    final NanoHTTPD.Method method = iHTTPSession.getMethod();
    final Map<String, String> params = iHTTPSession.getParms();
    if ((method == null) || (params == null)) {
      Log.d(LOG_TAG, "handle: unexpected request received: parameters are null");
      return null;
    }
    // Request must contain a query with radio ID and lock key
    // TODO: essayer avec params
    final String stringParams = params.get(PARAMS);
    final String[] stringsParams = (stringParams == null) ?
      new String[0] : stringParams.split(SEPARATOR);
    final String radioId = (stringsParams.length > 0) ? stringsParams[0] : null;
    final String lockKey = (stringsParams.length > 1) ? stringsParams[1] : null;
    if ((radioId == null) || (lockKey == null)) {
      Log.d(LOG_TAG, "handle: unexpected request received: radio parameters are null");
      return null;
    }
    final Radio radio = MainActivity.getRadios().getRadioFrom(radioId);
    if (radio == null) {
      Log.d(LOG_TAG, "handle: unknown radio");
      return null;
    }
    final boolean isGet = (method == NanoHTTPD.Method.GET);
    try {
      // Create WAN connection
      final HttpURLConnection httpURLConnection =
        new RadioURL(radio.getURL()).getActualHttpURLConnection(this::setHeader);
      Log.d(LOG_TAG, "Connected to radio " + (isGet ? "GET: " : "HEAD: ") + radio.getName());
      // Process with autocloseable feature
      final boolean isHls = HlsHandler.isHls(httpURLConnection);
      hlsHandler = isHls ? new HlsHandler(httpURLConnection, this::setHeader) : null;
      final ConnectionHandler connectionHandler = isHls ?
        new HlsConnectionHandler(
          hlsHandler::getRate,
          httpURLConnection,
          isGet,
          hlsHandler.getInputStream(),
          lockKey) :
        new RegularConnectionHandler(
          httpURLConnection,
          isGet,
          httpURLConnection.getInputStream(),
          lockKey);
      // Build response
      final NanoHTTPD.Response response = NanoHTTPD.newChunkedResponse(
        NanoHTTPD.Response.Status.OK,
        // Force ContentType as some UPnP devices require it
        controller.getContentType().isEmpty() ?
          httpURLConnection.getContentType() : controller.getContentType(),
        connectionHandler.getInputStream());
      connectionHandler.onLANConnection(response);
      final String contentType = controller.getContentType();
      // contentType defined only for UPnP
      if (!contentType.isEmpty()) {
        // DLNA header, as found in documentation, not sure it is useful (should not)
        response.addHeader("contentFeatures.dlna.org", "*");
        response.addHeader("transferMode.dlna.org", "Streaming");
      }
      // Update rate
      listener.onNewRate(connectionHandler.getRate(), lockKey);
      return response;
    } catch (Exception exception) {
      Log.d(LOG_TAG, "handle: unable to build response", exception);
    }
    return null;
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
    default String getKey() {
      return "";
    }

    // Only for UPnP
    @NonNull
    default String getContentType() {
      return "";
    }
  }

  // ICY data are not processed
  private class HlsConnectionHandler extends ConnectionHandler {
    @NonNull
    private final Supplier<String> rateSupplier;

    private HlsConnectionHandler(
      @NonNull Supplier<String> rateSupplier,
      @NonNull HttpURLConnection httpURLConnection,
      boolean isGet,
      @NonNull InputStream inputStream,
      @NonNull String lockKey) {
      super(httpURLConnection, isGet, inputStream, lockKey);
      this.rateSupplier = rateSupplier;
    }

    @NonNull
    @Override
    protected String getRate() {
      final String rate = rateSupplier.get();
      return (rate == null) ? super.getRate() : rate.substring(0, rate.length() - 3);
    }
  }

  private class RegularConnectionHandler extends ConnectionHandler {
    RegularConnectionHandler(
      @NonNull HttpURLConnection httpURLConnection,
      boolean isGet,
      @NonNull InputStream inputStream,
      @NonNull String lockKey) {
      super(httpURLConnection, isGet, inputStream, lockKey);
    }

    @NonNull
    @Override
    protected String getRate() {
      final String rate = httpURLConnection.getHeaderField("icy-br");
      return (rate == null) ? super.getRate() : rate;
    }

    @Override
    protected void onLANConnection(@NonNull NanoHTTPD.Response response) {
      // Forward headers
      for (String header : httpURLConnection.getHeaderFields().keySet()) {
        // ICY data not forwarded, as only used here
        if ((header != null) && !header.toLowerCase().startsWith("icy-")) {
          final String value = httpURLConnection.getHeaderField(header);
          if (value != null) {
            response.addHeader(header, value);
          }
        }
      }
    }

    @NonNull
    @Override
    protected Charset getCharset() {
      // Try to find charset
      final String contentEncoding = httpURLConnection.getContentEncoding();
      return (contentEncoding == null) ? super.getCharset() : Charset.forName(contentEncoding);
    }

    @Override
    protected int getMetadataOffset() {
      // Try to find metadataOffset
      final int metadataOffset;
      final List<String> headerMeta = httpURLConnection.getHeaderFields().get("icy-metaint");
      try {
        metadataOffset = (headerMeta == null) ? 0 : Integer.parseInt(headerMeta.get(0));
      } catch (NumberFormatException numberFormatException) {
        Log.w(LOG_TAG, "Malformed header icy-metaint, no metadata expected");
        return super.getMetadataOffset();
      }
      if (metadataOffset > 0) {
        Log.i(LOG_TAG, "Metadata expected at index: " + metadataOffset);
      } else if (metadataOffset == 0) {
        Log.i(LOG_TAG, "No metadata expected");
      } else {
        Log.w(LOG_TAG, "Wrong metadata value");
      }
      return Math.max(metadataOffset, super.getMetadataOffset());
    }
  }

  private abstract class ConnectionHandler {
    @NonNull
    final HttpURLConnection httpURLConnection;
    final boolean isGet;
    @NonNull
    final InputStream inputStream;
    @NonNull
    final String lockKey;

    private ConnectionHandler(
      @NonNull HttpURLConnection httpURLConnection,
      boolean isGet,
      @NonNull InputStream inputStream,
      @NonNull String lockKey) {
      this.httpURLConnection = httpURLConnection;
      this.isGet = isGet;
      this.inputStream = inputStream;
      this.lockKey = lockKey;
    }

    @NonNull
    public InputStream getInputStream() {
      return new InputStream() {
        final byte[] buffer = new byte[1];
        final CharsetDecoder charsetDecoder = getCharset().newDecoder();
        final int metadataOffset = getMetadataOffset();
        final ByteBuffer metadataBuffer = ByteBuffer.allocate(METADATA_MAX);
        int metadataBlockBytesRead = 0;
        int metadataSize = 0;

        @Override
        public int read() throws IOException {
          if (isGet) {
            while (lockKey.equals(controller.getKey()) && (inputStream.read(buffer) > 0)) {
              // Only stream data are transferred
              if ((metadataOffset == 0) || (++metadataBlockBytesRead <= metadataOffset)) {
                return buffer[0] & 0xFF;
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
          // Done!
          return -1;
        }

        @Override
        public void close() throws IOException {
          super.close();
          inputStream.close();
          if (hlsHandler != null) {
            hlsHandler.release();
          }
        }
      };
    }

    @NonNull
    protected String getRate() {
      return "";
    }

    protected void onLANConnection(@NonNull NanoHTTPD.Response response) {
    }

    @NonNull
    protected Charset getCharset() {
      return Charset.defaultCharset();
    }

    protected int getMetadataOffset() {
      return 0;
    }
  }
}