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
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class RadioHandler extends AbstractHandler {
  private static final String LOG_TAG = RadioHandler.class.getName();
  private static final int CONNECT_TIMEOUT = 5000;
  private static final int READ_TIMEOUT = 30000;
  private static final String GET = "GET";
  private static final String HEAD = "HEAD";
  // For ICY metadata
  private static final Pattern pattern = Pattern.compile(".*StreamTitle='([^;]*)';.*");
  @NonNull
  private final String mUserAgent;
  @NonNull
  private final RadioLibrary mRadioLibrary;
  @Nullable
  private Listener mListener;
  private final boolean mIsBuffered;

  RadioHandler(@NonNull String userAgent, @NonNull RadioLibrary radioLibrary, boolean isBuffered) {
    super();
    mUserAgent = userAgent;
    mRadioLibrary = radioLibrary;
    mListener = null;
    mIsBuffered = isBuffered;
  }

  @Override
  public void destroy() {
    super.destroy();
    // Ensure proper end
    removeListener();
  }

  // Shall be called before proxying
  public synchronized void setListener(@NonNull Listener listener) {
    mListener = listener;
  }

  public synchronized void removeListener() {
    mListener = null;
  }

  @Override
  public void handle(
    String target,
    Request baseRequest,
    HttpServletRequest request,
    HttpServletResponse response) {
    // Request must contain a query with radio ID
    String radioId = baseRequest.getParameter(Radio.RADIO_ID);
    if ((radioId == null) || (mListener == null)) {
      Log.d(LOG_TAG,
        "Unexpected request received. RadioId: " + radioId + " Listener: " + mListener);
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
            handleConnection(method.equals(GET), response, radio, mListener);
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
    @NonNull Object lockKey) {
    // Create WAN connection
    HttpURLConnection httpURLConnection = null;
    String radioName = radio.getName();
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
        httpURLConnection.setRequestProperty("Icy-MetaData", "1");
      }
      httpURLConnection.connect();
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
      if (isGet) {
        handleStreaming(httpURLConnection, outputStream, radioName, lockKey);
      }
    } catch (IOException iOException) {
      sendError(radioName, lockKey, iOException);
    } finally {
      if (httpURLConnection != null) {
        httpURLConnection.disconnect();
      }
    }
  }

  private void handleStreaming(
    @NonNull HttpURLConnection httpURLConnection,
    @NonNull OutputStream outputStream,
    @NonNull String radioName,
    @NonNull Object lockKey) {
    Log.d(LOG_TAG, "handleStreaming: entering for " + radioName);
    try (InputStream inputStream = httpURLConnection.getInputStream()) {
      // Try to find charset
      String contentType = httpURLConnection.getContentEncoding();
      Charset charset =
        (contentType == null) ? Charset.defaultCharset() : Charset.forName(contentType);
      CharsetDecoder charsetDecoder = charset.newDecoder();
      // Find metadata place, 0 if undefined
      int metaDataOffset = 0;
      List<String> headerMeta = httpURLConnection.getHeaderFields().get("icy-metaint");
      try {
        metaDataOffset = (headerMeta == null) ? 0 : Integer.parseInt(headerMeta.get(0));
      } catch (NumberFormatException numberFormatException) {
        Log.w(LOG_TAG, "Malformed header icy-metaint");
      }
      Log.d(LOG_TAG, (metaDataOffset > 0) ? "Metadata expected at index: " + metaDataOffset :
        "No metadata expected");
      // Forward stream data and handle metadata
      byte[] buffer = new byte[1];
      int bytesRead = 0; // Only used for metadata
      int metaDataSize = 0;
      byte[] metaDataBuffer = null;
      while (inputStream.read(buffer) != -1) {
        // Multithread safe!
        synchronized (this) {
          if (hasLockKey(lockKey)) {
            // Only stream data are transferred
            if ((metaDataOffset == 0) || (++bytesRead <= metaDataOffset)) {
              outputStream.write(buffer);
            } else {
              // Metadata: look for title information
              int metaDataIndex = bytesRead - metaDataOffset - 1;
              // First byte gives size (16 bytes chunks) to read for metadata
              if (metaDataIndex == 0) {
                metaDataSize = buffer[0] * 16;
                metaDataBuffer = new byte[metaDataSize];
              } else {
                // Other bytes are metadata
                if (metaDataBuffer == null) {
                  Log.e(LOG_TAG, "Internal error; metaDataBuffer not instantiated");
                } else {
                  metaDataBuffer[metaDataIndex - 1] = buffer[0];
                }
              }
              // End of metadata
              // Extract ICY title
              if (metaDataIndex == metaDataSize) {
                String metaData = null;
                try {
                  metaData = charsetDecoder
                    .decode(ByteBuffer.wrap(Arrays.copyOf(metaDataBuffer, metaDataSize)))
                    .toString();
                  Log.d(LOG_TAG, "Metadata found at index [" + metaDataOffset +
                    "], size[" + metaDataSize + "]: " + metaData);
                } catch (Exception exception) {
                  Log.w(LOG_TAG, "Error decoding metadata", exception);
                }
                Matcher matcher = (metaData == null) ? null : pattern.matcher(metaData);
                // Output radio information
                if ((matcher != null) && matcher.find() && (mListener != null)) {
                  mListener.onNewInformation(matcher.group(1));
                }
                bytesRead = 0;
              }
            }
          } else {
            Log.d(LOG_TAG, "Proxying requested to stop");
            break;
          }
        }
      }
    } catch (IOException iOException) {
      sendError(radioName, lockKey, iOException);
    }
    Log.d(LOG_TAG, "handleStreaming: leaving for " + radioName);
  }

  private synchronized boolean hasLockKey(Object lockKey) {
    return (mListener != null) && (mListener == lockKey);
  }

  private synchronized void sendError(String radioName, Object lockKey, IOException iOException) {
    Log.d(LOG_TAG, "IOException for: " + radioName, iOException);
    // Error thrown only if allowed to run
    if (hasLockKey(lockKey)) {
      Log.d(LOG_TAG, "Error sent to listener for: " + radioName);
      assert mListener != null;
      mListener.onError();
    }
  }

  public interface Listener {
    void onNewInformation(@NonNull String information);

    void onError();
  }
}