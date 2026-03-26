/*
 * Copyright (c) 2026. Stephane Treuchot
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

import android.graphics.Bitmap;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;

import com.watea.candidhttpserver.HttpServer;
import com.watea.radio_upnp.model.Radio;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

@OptIn(markerClass = UnstableApi.class)
public class UpnpStreamServer extends HttpServer {
  public static final String PCM_MIME = "audio/wav";
  public static final String DEFAULT_MIME = "audio/mpeg";
  private static final String TAG = "UpnpStreamServer";
  private static final String SCHEME = "http://";
  private static final int DEFAULT = -1;
  private static final int GET_TIMEOUT = 10000; // ms
  private static final int QUEUE_SIZE = 300; // ~10s buffer at 48000Hz stereo 16-bit (4608 bytes/chunk)
  private static final int PACER_POLL_TIMEOUT = 15; // s
  private static final int REMOTE_LOGO_SIZE = 300;
  private static final String LOGO_PREFIX = "logo";
  private static final String LOGO_SUFFIX = ".jpg";
  private static final String STREAM_PREFIX = "stream-";
  private static final String STREAM_SUFFIX = ".wav";
  private static final int PIPE_BUFFER_SIZE = 8192;
  @NonNull
  private final Callback callback;
  private final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(QUEUE_SIZE);
  private volatile String lockKey = RadioService.getLockKey(); // Current stream signature
  @Nullable
  private volatile URL relayUrl = null;
  // Audio format — set by setAudioFormat(), DEFAULT until ExoPlayer codec is configured.
  // Volatile because setAudioFormat() runs on an ExoPlayer thread while StreamHandler
  // may be polling these values from the CandidHttpServer thread.
  private volatile int sampleRate = DEFAULT;
  private volatile int channelCount = DEFAULT;
  private volatile int bitsPerSample = DEFAULT;
  @Nullable
  private String logoPath = null;
  @Nullable
  private byte[] logoBytes = null;

  public UpnpStreamServer(@NonNull Callback callback) throws IOException {
    this.callback = callback;
    addHandler(new LogoHandler());
    addHandler(new StreamHandler());
  }

  @Nullable
  private static String extractLockKey(@NonNull String path) {
    // Path is "/<STREAM_PREFIX><lockKey><STREAM_SUFFIX>"
    final String name = path.startsWith("/") ? path.substring(1) : path;
    if (name.startsWith(STREAM_PREFIX) && name.endsWith(STREAM_SUFFIX)) {
      return name.substring(STREAM_PREFIX.length(), name.length() - STREAM_SUFFIX.length());
    }
    return null;
  }

  // Builds a standard 44-byte WAV header.
  // Size fields are set to 0xFFFFFFFF to indicate an unbounded stream,
  // which is the common practice for HTTP audio streaming.
  @NonNull
  private static byte[] buildWavHeader(int sampleRate, int channelCount, int bitsPerSample) {
    final int byteRate = sampleRate * channelCount * (bitsPerSample / 8);
    final int blockAlign = channelCount * (bitsPerSample / 8);
    final ByteBuffer buf = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
    buf.put(new byte[]{'R', 'I', 'F', 'F'});
    buf.putInt(0xFFFFFFFF); // Unknown file size — streaming
    buf.put(new byte[]{'W', 'A', 'V', 'E'});
    buf.put(new byte[]{'f', 'm', 't', ' '});
    buf.putInt(16); // fmt chunk size
    buf.putShort((short) 1); // PCM format
    buf.putShort((short) channelCount);
    buf.putInt(sampleRate);
    buf.putInt(byteRate);
    buf.putShort((short) blockAlign);
    buf.putShort((short) bitsPerSample);
    buf.put(new byte[]{'d', 'a', 't', 'a'});
    buf.putInt(0xFFFFFFFF); // Unknown data size — streaming
    return buf.array();
  }

  @NonNull
  public CapturingAudioSink.Callback getPcmCallback() {
    return new CapturingAudioSink.Callback() {
      @Override
      public void onFormatChanged(int sampleRate, int channelCount, int bitsPerSample) {
        setAudioFormat(sampleRate, channelCount, bitsPerSample);
      }

      @Override
      public void onPcmData(@NonNull byte[] pcmData) {
        feed(pcmData);
      }
    };
  }

  @NonNull
  public Uri setLogo(@NonNull final Radio radio, @NonNull final String localIp) {
    final Bitmap bitmap = Bitmap.createScaledBitmap(radio.getIcon(), REMOTE_LOGO_SIZE, REMOTE_LOGO_SIZE, true);
    final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, byteArrayOutputStream);
    logoBytes = byteArrayOutputStream.toByteArray();
    logoPath = "/" + LOGO_PREFIX + radio.getId() + LOGO_SUFFIX;
    return Uri.parse(SCHEME + localIp + ":" + getListeningPort() + logoPath);
  }

  // Called early in PCM mode to sync lockKey with RadioService immediately,
  // before ExoPlayer initialises the codec. This allows StreamHandler to
  // validate the session and send HTTP response headers without delay —
  // the audio format will follow via setAudioFormat() once the codec is ready.
  public void setPcmMode() {
    Log.d(TAG, "setPcmMode");
    relayUrl = null;
    sampleRate = DEFAULT; // Format not yet known — setAudioFormat() will fill this in
    lockKey = callback.getLockKey();
  }

  // Called by CapturingAudioSink.configure() once ExoPlayer has initialised the
  // codec (~500-750ms after setPcmMode). At this point StreamHandler may already
  // be waiting for a valid sampleRate to write the WAV header.
  public void setAudioFormat(int sampleRate, int channelCount, int bitsPerSample) {
    Log.d(TAG, "setAudioFormat");
    this.sampleRate = sampleRate;
    this.channelCount = channelCount;
    this.bitsPerSample = bitsPerSample;
    // lockKey already synced by setPcmMode()
  }

  public void setRelayUrl(@NonNull URL url) {
    Log.d(TAG, "setRelayUrl");
    relayUrl = url;
    sampleRate = DEFAULT; // Reset PCM format
    lockKey = callback.getLockKey();
  }

  public void feed(@NonNull final byte[] pcmData) {
    final int remaining = queue.remainingCapacity();
    if (remaining < QUEUE_SIZE * 0.2F) {
      Log.w(TAG, "Queue fill: " + (QUEUE_SIZE - remaining) + "/" + QUEUE_SIZE);
    }
    if (!queue.offer(pcmData)) {
      Log.e(TAG, "QUEUE FULL => DROP (" + pcmData.length + " bytes)");
    }
  }

  // Returns the stream URL to pass to the renderer via SetAVTransportURI
  public Uri getStreamUri(@NonNull String localIp, @NonNull String lockKey) {
    return Uri.parse(SCHEME + localIp + ":" + getListeningPort() + "/" + STREAM_PREFIX + lockKey + STREAM_SUFFIX);
  }

  // Adds DLNA streaming headers common to both PCM and relay responses
  private void addDlnaHeaders(@NonNull HttpServer.Response response, @NonNull String mime) {
    response.addHeader("transferMode.dlna.org", "Streaming");
    final String dlnaOrgPn;
    if (relayUrl == null) {
      dlnaOrgPn = "DLNA.ORG_PN=LPCM;";
    } else {
      switch (mime) {
        case "audio/mpeg":
          dlnaOrgPn = "DLNA.ORG_PN=MP3;";
          break;
        case "audio/aac":
        case "audio/x-aac":
        case "audio/mp4":
          dlnaOrgPn = "DLNA.ORG_PN=AAC_ISO_MBLA;";
          break;
        default:
          dlnaOrgPn = ""; // OGG, FLAC, etc. — no standard DLNA profile
      }
    }
    response.addHeader("contentFeatures.dlna.org", dlnaOrgPn + "DLNA.ORG_OP=00;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000");
    response.addHeader(Response.CONTENT_TYPE, mime);
  }

  // Pipes src into dst until EOF or lockKey becomes stale, then closes src
  private void pipe(
    @NonNull InputStream src,
    @NonNull OutputStream dst,
    @NonNull String lockKey) throws IOException {
    final byte[] buf = new byte[PIPE_BUFFER_SIZE];
    int n;
    try (src) {
      while ((n = src.read(buf)) != -1) {
        if (!lockKey.equals(callback.getLockKey())) {
          Log.d(TAG, "pipe: stream stopped");
          break;
        }
        dst.write(buf, 0, n);
      }
    }
  }

  public interface Callback {
    void onDisconnect(@NonNull String lockKey);

    void onConnected(@NonNull String lockKey);

    @NonNull
    String getLockKey();
  }

  // Serves the radio logo as JPEG
  private class LogoHandler implements HttpServer.Handler {
    @Override
    public void handle(
      @NonNull HttpServer.Request request,
      @NonNull HttpServer.Response response,
      @NonNull OutputStream responseStream) throws IOException {
      if (logoPath == null || !logoPath.equals(request.getPath())) {
        return; // Not our path — let StreamHandler try
      }
      if (logoBytes == null) {
        Log.e(TAG, "LogoHandler: no logo available");
        return;
      }
      Log.d(TAG, "LogoHandler: serving logo");
      response.addHeader(Response.CONTENT_TYPE, "image/jpeg");
      response.addHeader(Response.CONTENT_LENGTH, String.valueOf(logoBytes.length));
      response.send();
      responseStream.write(logoBytes);
    }
  }

  // Serves the audio stream in PCM/WAV mode or relay (passthrough) mode
  private class StreamHandler implements HttpServer.Handler {
    @Override
    public void handle(
      @NonNull HttpServer.Request request,
      @NonNull HttpServer.Response response,
      @NonNull OutputStream responseStream) throws IOException {
      final String incomingLockKey = extractLockKey(request.getPath());
      if (incomingLockKey == null) {
        return; // Not a stream request — not handled
      }
      final String method = request.getMethod();
      Log.d(TAG, "StreamHandler: " + method + " lockKey = " + incomingLockKey);
      final boolean isGet = method.equals("GET");
      final boolean isHead = method.equals("HEAD");
      if (!(isHead || isGet)) {
        Log.d(TAG, "StreamHandler: not a valid request");
        return;
      }
      // Wait for lockKey to be synced (set by setPcmMode() or setRelayUrl())
      final long deadline = System.currentTimeMillis() + GET_TIMEOUT;
      while (!callback.getLockKey().equals(lockKey)) {
        if (System.currentTimeMillis() > deadline) {
          Log.w(TAG, "StreamHandler: not ready yet");
          return;
        }
        try {
          //noinspection BusyWait
          Thread.sleep(50);
        } catch (InterruptedException interruptedException) {
          Thread.currentThread().interrupt();
          return;
        }
      }
      // Validate lock key
      if (!incomingLockKey.equals(callback.getLockKey())) {
        Log.d(TAG, "StreamHandler: stale session");
        return;
      }
      queue.clear();
      callback.onConnected(incomingLockKey);
      if (relayUrl == null) {
        handlePcm(response, responseStream, incomingLockKey, isHead);
      } else {
        handleRelay(response, responseStream, incomingLockKey, isHead);
      }
    }

    // PCM/WAV streaming: sends HTTP headers immediately, then waits for the audio
    // format to be set by setAudioFormat() before writing the WAV header and PCM data.
    // This two-phase approach is necessary because the renderer may connect before
    // ExoPlayer has initialised the codec (~500-750ms after session start).
    private void handlePcm(
      @NonNull HttpServer.Response response,
      @NonNull OutputStream responseStream,
      @NonNull String lockKey,
      boolean isHead) throws IOException {
      Log.d(TAG, "handlePcm: PCM " + lockKey);
      // Send HTTP headers immediately — the renderer must not wait on a cold socket
      response.addHeader(Response.CONTENT_LENGTH, String.valueOf(Long.MAX_VALUE)); // Fake length for streaming WAV
      addDlnaHeaders(response, PCM_MIME);
      response.send();
      if (isHead) {
        return;
      }
      // Wait for ExoPlayer to configure the codec and call setAudioFormat()
      final long deadline = System.currentTimeMillis() + GET_TIMEOUT;
      while (sampleRate == DEFAULT) {
        if (System.currentTimeMillis() > deadline) {
          Log.e(TAG, "handlePcm: timeout waiting for audio format");
          callback.onDisconnect(lockKey);
          return;
        }
        try {
          //noinspection BusyWait
          Thread.sleep(50);
        } catch (InterruptedException interruptedException) {
          Thread.currentThread().interrupt();
          callback.onDisconnect(lockKey);
          return;
        }
      }
      // WAV header first, then raw PCM drained from queue
      responseStream.write(buildWavHeader(sampleRate, channelCount, bitsPerSample));
      Log.i(TAG, "handlePcm: renderer started reading PCM stream");
      try {
        while (lockKey.equals(callback.getLockKey())) {
          final byte[] pcmData = queue.poll(PACER_POLL_TIMEOUT, TimeUnit.SECONDS);
          if (pcmData == null) {
            Log.w(TAG, "handlePcm: PCM timeout (" + PACER_POLL_TIMEOUT + "s) — stream stalled");
            break;
          }
          responseStream.write(pcmData);
        }
      } catch (InterruptedException interruptedException) {
        Thread.currentThread().interrupt();
      } finally {
        callback.onDisconnect(lockKey);
      }
    }

    // Relay (passthrough) streaming: connects upstream, then sends headers, then pipes.
    // The upstream connection (~hundreds of ms) completes before response.send() so
    // Content-Type is accurate. Headers reach the renderer immediately after — no delay
    // on the socket, which keeps strict renderers like Fire TV / stagefright happy.
    private void handleRelay(
      @NonNull HttpServer.Response response,
      @NonNull OutputStream responseStream,
      @NonNull String lockKey,
      boolean isHead) throws IOException {
      Log.d(TAG, "handleRelay: relay " + lockKey);
      final HttpURLConnection httpURLConnection = new RadioURL(relayUrl)
        .getActualHttpURLConnection(conn -> conn.setRequestProperty("Icy-MetaData", "0")); // ExoPlayer handles ICY locally
      final String contentType = RadioURL.getStreamContentType(httpURLConnection);
      final String mime = (contentType != null) ? contentType : DEFAULT_MIME;
      Log.d(TAG, "handleRelay: relay mime = " + mime);
      addDlnaHeaders(response, mime);
      response.send(); // Headers on the wire — body follows immediately
      if (isHead) {
        httpURLConnection.disconnect();
        return;
      }
      try {
        pipe(httpURLConnection.getInputStream(), responseStream, lockKey);
      } finally {
        httpURLConnection.disconnect();
        callback.onDisconnect(lockKey);
      }
    }
  }
}