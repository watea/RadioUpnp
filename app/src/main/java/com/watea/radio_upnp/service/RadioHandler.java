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

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLConnection;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class RadioHandler extends AbstractHandler {
  private static final String LOG_TAG = RadioHandler.class.getName();
  private static final int METADATA_MAX = 256;
  private static final String PARAMS = "params";
  private static final String SEPARATOR = "_";
  private static final Controller DEFAULT_CONTROLLER = new Controller() {
  };
  private static final Listener DEFAULT_LISTENER = new Listener() {
  };
  private static final Pattern PATTERN_ICY = Pattern.compile(".*StreamTitle='([^;]*)';.*");
  @NonNull
  private final String userAgent;
  @NonNull
  private Listener listener = DEFAULT_LISTENER;
  @NonNull
  private Controller controller = DEFAULT_CONTROLLER;

  public RadioHandler(@NonNull String userAgent) {
    super();
    this.userAgent = userAgent;
  }

  // Add ID and lock key to given URI as query parameter.
  // Don't use several query parameters to avoid encoding troubles.
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
  public void handle(
    String target,
    Request baseRequest,
    HttpServletRequest request,
    HttpServletResponse response) {
    Log.d(LOG_TAG, "handle");
    // Valid request
    if ((baseRequest == null) || (request == null)) {
      Log.d(LOG_TAG, "Unexpected request received: request || baseRequest is null");
      return;
    }
    final String param = request.getParameter(PARAMS);
    if (param == null) {
      Log.d(LOG_TAG, "Unexpected request received: param is null");
      return;
    }
    // Request must contain a query with radio ID and lock key
    final String[] params = param.split(SEPARATOR);
    final String id = (params.length > 0) ? params[0] : null;
    final String lockKey = (params.length > 1) ? params[1] : null;
    if ((id == null) || (lockKey == null)) {
      Log.i(LOG_TAG, "Unexpected request received. Radio or UUID is null.");
    } else {
      final Radio radio = MainActivity.getRadios().getRadioFrom(id);
      if (radio == null) {
        Log.i(LOG_TAG, "Unknown radio");
      } else {
        baseRequest.setHandled(true);
        final String method = request.getMethod();
        final boolean isGet = (method == null) || method.equals("GET");
        HlsHandler hlsHandler = null;
        try (final OutputStream outputStream = response.getOutputStream()) {
          // Create WAN connection
          final HttpURLConnection httpURLConnection =
            new RadioURL(radio.getURL()).getActualHttpURLConnection(this::setHeader);
          Log.d(LOG_TAG, "Connected to radio " + (isGet ? "GET: " : "HEAD: ") + radio.getName());
          // Process with autocloseable feature
          final boolean isHls = HlsHandler.isHls(httpURLConnection);
          if (isHls) {
            hlsHandler = new HlsHandler(httpURLConnection, this::setHeader, outputStream::flush);
            try (final InputStream inputStream = hlsHandler.getInputStream()) {
              new HlsConnectionHandler(
                hlsHandler::getRate,
                httpURLConnection,
                isGet,
                inputStream,
                outputStream,
                response,
                lockKey).handleConnection();
            }
          } else {
            try (final InputStream inputStream = httpURLConnection.getInputStream()) {
              new RegularConnectionHandler(
                httpURLConnection,
                isGet,
                inputStream,
                outputStream,
                response,
                lockKey).handleConnection();
            }
          }
        } catch (Exception exception) {
          Log.d(LOG_TAG, "Connection to radio interrupted", exception);
        } finally {
          if (hlsHandler != null) {
            hlsHandler.release();
          }
        }
      }
    }
    Log.d(LOG_TAG, "handle: leaving");
  }

  // Must be called
  public void bind(@NonNull Listener listener) {
    this.listener = listener;
  }

  // Must be called to close
  public void unBind() {
    resetController();
    listener = DEFAULT_LISTENER;
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
      @NonNull OutputStream outputStream,
      @NonNull HttpServletResponse response,
      @NonNull String lockKey) {
      super(httpURLConnection, isGet, inputStream, outputStream, response, lockKey);
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
      @NonNull OutputStream outputStream,
      @NonNull HttpServletResponse response,
      @NonNull String lockKey) {
      super(httpURLConnection, isGet, inputStream, outputStream, response, lockKey);
    }

    @NonNull
    @Override
    protected String getRate() {
      final String rate = httpURLConnection.getHeaderField("icy-br");
      return (rate == null) ? super.getRate() : rate;
    }

    @Override
    protected void onLANConnection() {
      // Forward headers
      for (String header : httpURLConnection.getHeaderFields().keySet()) {
        // ICY data not forwarded, as only used here
        if ((header != null) && !header.toLowerCase().startsWith("icy-")) {
          final String value = httpURLConnection.getHeaderField(header);
          if (value != null) {
            response.setHeader(header, value);
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
    final byte[] buffer = new byte[1];
    @NonNull
    final HttpURLConnection httpURLConnection;
    final boolean isGet;
    @NonNull
    final InputStream inputStream;
    @NonNull
    final OutputStream outputStream;
    @NonNull
    final HttpServletResponse response;
    @NonNull
    final String lockKey;

    private ConnectionHandler(
      @NonNull HttpURLConnection httpURLConnection,
      boolean isGet,
      @NonNull InputStream inputStream,
      @NonNull OutputStream outputStream,
      @NonNull HttpServletResponse response,
      @NonNull String lockKey) {
      this.httpURLConnection = httpURLConnection;
      this.isGet = isGet;
      this.inputStream = inputStream;
      this.outputStream = outputStream;
      this.response = response;
      this.lockKey = lockKey;
    }

    @NonNull
    protected String getRate() {
      return "";
    }

    protected void onLANConnection() {
    }

    @NonNull
    protected Charset getCharset() {
      return Charset.defaultCharset();
    }

    protected int getMetadataOffset() {
      return 0;
    }

    void handleConnection() throws IOException {
      // Update rate
      listener.onNewRate(getRate(), lockKey);
      // Response to LAN
      onLANConnection();
      final String contentType = controller.getContentType();
      // contentType defined only for UPnP
      if (!contentType.isEmpty()) {
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
        // Launch streaming
        handleStreaming(getCharset().newDecoder(), getMetadataOffset());
      }
    }

    // Forward stream data and handle metadata.
    // metadataOffset = 0 if no metadata.
    private void handleStreaming(
      @NonNull final CharsetDecoder charsetDecoder, final int metadataOffset) throws IOException {
      Log.d(LOG_TAG, "handleStreaming");
      final ByteBuffer metadataBuffer = ByteBuffer.allocate(METADATA_MAX);
      int metadataBlockBytesRead = 0;
      int metadataSize = 0;
      // Stop if not current controller
      while (lockKey.equals(controller.getKey()) && (inputStream.read(buffer) > 0)) {
        // Only stream data are transferred
        if ((metadataOffset == 0) || (++metadataBlockBytesRead <= metadataOffset)) {
          outputStream.write(buffer);
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
      Log.d(LOG_TAG, "handleStreaming: leaving");
    }
  }
}