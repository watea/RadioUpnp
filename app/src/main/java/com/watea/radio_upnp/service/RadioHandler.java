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

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
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
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class RadioHandler extends AbstractHandler {
  private static final String LOG_TAG = RadioHandler.class.getName();
  private static final String VOID = "";
  private static final int CONNECT_TIMEOUT = 5000;
  private static final int READ_TIMEOUT = 30000;
  private static final int METADATA_MAX = 256;
  private static final String GET = "GET";
  private static final String HEAD = "HEAD";
  // For ICY metadata
  private static final Pattern PATTERN = Pattern.compile(".*StreamTitle='([^;]*)';.*");
  @NonNull
  private final String mUserAgent;
  @NonNull
  private final RadioLibrary mRadioLibrary;
  private final boolean mIsBuffered;
  @Nullable
  private Listener mListener = null;
  @NonNull
  private String mLockKey = VOID;

  RadioHandler(@NonNull String userAgent, @NonNull RadioLibrary radioLibrary, boolean isBuffered) {
    super();
    mUserAgent = userAgent;
    mRadioLibrary = radioLibrary;
    mIsBuffered = isBuffered;
  }

  @Override
  public void destroy() {
    super.destroy();
    // Ensure proper end
    removeListener();
  }

  // Shall be called before proxying
  public synchronized void setListener(@NonNull Listener listener, @NonNull String lockKey) {
    mListener = listener;
    mLockKey = lockKey;
  }

  public synchronized void removeListener() {
    mListener = null;
    mLockKey = VOID;
  }

  @Override
  public void handle(
    String target,
    Request baseRequest,
    HttpServletRequest request,
    HttpServletResponse response) {
    // Tag this handle with current lock key
    final String lockKey = mLockKey;
    // Request must contain a query with radio ID
    String radioId = baseRequest.getParameter(Radio.RADIO_ID);
    if ((radioId == null) || lockKey.equals(VOID)) {
      Log.d(LOG_TAG,
        "Unexpected request received. RadioId: " + radioId + "LockKey: " + lockKey);
    } else {
      Radio radio = mRadioLibrary.getFrom(Long.decode(radioId));
      if (radio == null) {
        Log.d(LOG_TAG, "Unknown radio");
      } else {
        baseRequest.setHandled(true);
        String method = baseRequest.getMethod();
        Log.d(LOG_TAG, method + " radio request received, radio: " + radio.getName());
        switch (method) {
          case HEAD:
          case GET:
            handleConnection(method.equals(GET), response, radio, lockKey);
            break;
          default:
            Log.d(LOG_TAG, "Unknown radio request received:" + method);
        }
      }
    }
  }

  private void handleConnection(
    boolean isGet,
    @NonNull HttpServletResponse response,
    @NonNull Radio radio,
    @NonNull String lockKey) {
    Log.d(LOG_TAG,
      "handleConnection: entering for " + radio.getName() + "; " + (isGet ? GET : HEAD));
    // Create WAN connection
    HttpURLConnection httpURLConnection = null;
    try (OutputStream outputStream = mIsBuffered ?
      new BufferedOutputStream(response.getOutputStream()) : response.getOutputStream()) {
      httpURLConnection = (HttpURLConnection) radio.getURL().openConnection();
      httpURLConnection.setInstanceFollowRedirects(true);
      httpURLConnection.setConnectTimeout(CONNECT_TIMEOUT);
      httpURLConnection.setReadTimeout(READ_TIMEOUT);
      httpURLConnection.setRequestMethod(isGet ? GET : HEAD);
      httpURLConnection.setRequestProperty("User-Agent", mUserAgent);
      httpURLConnection.setRequestProperty("GetContentFeatures.dlna.org", "1");
      if (isGet) {
        httpURLConnection.setRequestProperty("Icy-metadata", "1");
      }
      httpURLConnection.connect();
      Log.d(LOG_TAG, "handleConnection: connected to radio URL");
      // Response to LAN
      response.setContentType("audio/mpeg");
      response.setHeader("Server", mUserAgent);
      response.setHeader("Accept-Ranges", "bytes");
      response.setHeader("Connection", "Keep-Alive");
      /* DLNA.ORG_FLAGS, padded with 24 trailing 0s
       *     80000000  31  senderPaced
       *     40000000  30  lsopTimeBasedSeekSupported
       *     20000000  29  lsopByteBasedSeekSupported
       *     10000000  28  playcontainerSupported
       *      8000000  27  s0IncreasingSupported
       *      4000000  26  sNIncreasingSupported
       *      2000000  25  rtspPauseSupported
       *      1000000  24  streamingTransferModeSupported
       *       800000  23  interactiveTransferModeSupported
       *       400000  22  backgroundTransferModeSupported
       *       200000  21  connectionStallingSupported
       *       100000  20  dlnaVersion15Supported
       *
       *     Example: (1 << 24) | (1 << 22) | (1 << 21) | (1 << 20)
       *       DLNA.ORG_FLAGS=01700000[000000000000000000000000] // [] show padding
       *
       * If DLNA.ORG_OP=11, then left/rght keys uses range header, and up/down uses TimeSeekRange.DLNA.ORG header
       * If DLNA.ORG_OP=10, then left/rght and up/down keys uses TimeSeekRange.DLNA.ORG header
       * If DLNA.ORG_OP=01, then left/rght keys uses range header, and up/down keys are disabled
       * and if DLNA.ORG_OP=00, then all keys are disabled */
      response.setHeader("ContentFeatures.dlna.org", "DLNA.ORG_PN=MP3;DLNA.ORG_OP=00;DLNA.ORG_FLAGS=01700000000000000000000000000000");
      response.setHeader("TransferMode.dlna.org", "Streaming");
      response.setStatus(HttpServletResponse.SC_OK);
      response.flushBuffer();
      Log.d(LOG_TAG, "handleConnection: response sent to LAN client");
      if (isGet) {
        // Try to find charset
        String contentType = httpURLConnection.getContentEncoding();
        Charset charset = (contentType == null) ?
          Charset.defaultCharset() : Charset.forName(contentType);
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
          lockKey);
      }
    } catch (IOException iOException) {
      // Error thrown if allowed to run
      Log.d(LOG_TAG, "IOException", iOException);
      // Multithread safe!
      synchronized (this) {
        if (mListener != null) {
          Log.d(LOG_TAG, "Error sent to listener");
          mListener.onError(lockKey);
        }
      }
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
    @NonNull String lockKey) throws IOException {
    Log.d(LOG_TAG, "handleStreaming: entering");
    final byte[] buffer = new byte[1];
    final ByteBuffer metadataBuffer = ByteBuffer.allocate(METADATA_MAX);
    int metadataBlockBytesRead = 0;
    int metadataSize = 0;
    while (inputStream.read(buffer) != -1) {
      // Multithread safe!
      synchronized (this) {
        if (mLockKey.equals(lockKey)) {
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
                Matcher matcher = PATTERN.matcher(metadata);
                // Tell listener
                if ((mListener != null) && matcher.find()) {
                  mListener.onNewInformation(matcher.group(1), lockKey);
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
    }
    Log.d(LOG_TAG, "handleStreaming: leaving");
  }

  public interface Listener {
    void onNewInformation(@NonNull String information, @NonNull String lockKey);

    void onError(@NonNull String lockKey);
  }
}