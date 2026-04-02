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
import android.os.Looper;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@OptIn(markerClass = UnstableApi.class)
public class UpnpStreamServer extends HttpServer {
  public static final String PCM_MIME = "audio/wav";
  private static final String DEFAULT_MIME = "audio/mpeg";
  private static final String LOG_TAG = "UpnpStreamServer";
  private static final String SCHEME = "http://";
  private static final int DEFAULT = -1;
  private static final int GET_TIMEOUT = 10000; // ms
  private static final int QUEUE_SIZE = 300; // ~10s buffer at 48000Hz stereo 16-bit (4608 bytes/chunk)
  private static final int PACER_POLL_TIMEOUT = 15; // s
  private static final int REMOTE_LOGO_SIZE = 300;
  private static final String LOGO_PREFIX = "logo";
  private static final String LOGO_SUFFIX = ".jpg";
  private static final String STREAM_PREFIX = "stream-";
  private static final String STREAM_SUFFIX_PCM = ".wav";
  private static final int PIPE_BUFFER_SIZE = 8192;
  private static final int CONNECT_WATCHDOG_TIMEOUT_S = 30;
  private static final int LIVELINESS_WATCHDOG_TIMEOUT_S = 20;
  private static final Pattern STREAM_PATH_PATTERN = Pattern.compile(
    "^/" + STREAM_PREFIX + "([^/]+?)" + "(?:" + Pattern.quote(STREAM_SUFFIX_PCM) + ")?$");
  @NonNull
  private final Callback callback;
  private final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(QUEUE_SIZE);
  private final Watchdogs watchdogs = new Watchdogs();
  private final AtomicInteger session = new AtomicInteger(0);
  private final ConcurrentHashMap<String, HttpURLConnection> httpURLConnections = new ConcurrentHashMap<>();
  @NonNull
  private volatile String lockKey = RadioService.getLockKey(); // Current stream signature
  // Audio format — set by setAudioFormat(), DEFAULT until ExoPlayer codec is configured.
  // Volatile because setAudioFormat() runs on an ExoPlayer thread while StreamHandler
  // may be polling these values from the CandidHttpServer thread.
  private volatile int sampleRate = DEFAULT;
  private volatile int channelCount = DEFAULT;
  private volatile int bitsPerSample = DEFAULT;
  private final CapturingAudioSink.Callback capturingAudioSinkCallback = new CapturingAudioSink.Callback() {
    // Must be called before any PCM streaming is started
    @Override
    public void onFormatChanged(int sampleRate, int channelCount, int bitsPerSample) {
      Log.d(LOG_TAG, "onFormatChanged");
      UpnpStreamServer.this.sampleRate = sampleRate;
      UpnpStreamServer.this.channelCount = channelCount;
      UpnpStreamServer.this.bitsPerSample = bitsPerSample;
    }

    @Override
    public void onPcmData(@NonNull byte[] pcmData, @NonNull String lockKey) {
      if (!UpnpStreamServer.this.lockKey.equals(lockKey)) {
        return;
      }
      final int remaining = queue.remainingCapacity();
      if (remaining < QUEUE_SIZE * 0.2F) {
        Log.w(LOG_TAG, "Queue fill: " + (QUEUE_SIZE - remaining) + "/" + QUEUE_SIZE);
      }
      if (!queue.offer(pcmData)) {
        Log.e(LOG_TAG, "QUEUE FULL => DROP (" + pcmData.length + " bytes)");
      }
    }
  };
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
    final Matcher matcher = STREAM_PATH_PATTERN.matcher(path);
    return matcher.matches() ? matcher.group(1) : null;
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
    return capturingAudioSinkCallback;
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

  @Nullable
  public String setHttpURLConnectionAndGetContentType(@NonNull URL url, @NonNull String lockKey) {
    String result = null;
    try {
      final HttpURLConnection httpURLConnection = new RadioURL(url)
        .getActualHttpURLConnection(conn -> conn.setRequestProperty("Icy-MetaData", "0")); // No ICY
      httpURLConnections.put(lockKey, httpURLConnection);
      result = RadioURL.getStreamContentType(httpURLConnection);
      result = (result == null) ? DEFAULT_MIME : result;
      Log.d(LOG_TAG, "setHttpURLConnectionAndGetContentType: content => " + result);
    } catch (IOException ioException) {
      Log.d(LOG_TAG, "setHttpURLConnectionAndGetContentType: unable to connect", ioException);
    }
    return result;
  }

  // Must be called early before any session is started
  public void setLockKey(@NonNull String lockKey) {
    Log.d(LOG_TAG, "setLockKey: " + lockKey);
    this.lockKey = lockKey;
    session.set(0);
    sampleRate = DEFAULT;
  }

  public void launchWatchdog(@NonNull String lockKey) {
    watchdogs.launch(this::onConnectionFailure, lockKey, CONNECT_WATCHDOG_TIMEOUT_S);
  }

  // Returns stream URI adapted to current mode (PCM vs relay)
  public Uri getStreamUri(@NonNull String localIp, @NonNull String lockKey, boolean isPcm) {
    return Uri.parse(SCHEME + localIp + ":" + getListeningPort() + "/" + STREAM_PREFIX + lockKey + (isPcm ? STREAM_SUFFIX_PCM : ""));
  }

  // Adds DLNA streaming headers common to both PCM and relay responses
  private void addDlnaHeaders(@NonNull HttpServer.Response response, @NonNull String mime, boolean isPcm) {
    response.addHeader("transferMode.dlna.org", "Streaming");
    final String dlnaOrgPn;
    if (isPcm) {
      dlnaOrgPn = "DLNA.ORG_PN=LPCM;";
    } else {
      switch (mime) {
        case "audio/mpeg":
          dlnaOrgPn = "DLNA.ORG_PN=MP3;";
          break;
        case "audio/aac":
        case "audio/x-aac":
        case "audio/aacp":
          dlnaOrgPn = "DLNA.ORG_PN=AAC_ADTS;";
          break;
        case "audio/mp4":
        case "audio/x-m4a":
          dlnaOrgPn = "DLNA.ORG_PN=AAC_ISO;";
          break;
        case "audio/flac":
        case "audio/x-flac":
          // No standard DLNA profile for FLAC
        default:
          // OGG, unknown — no DLNA profile
          dlnaOrgPn = "";
      }
    }
    response.addHeader("contentFeatures.dlna.org", dlnaOrgPn + "DLNA.ORG_OP=00;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000");
    response.addHeader(Response.CONTENT_TYPE, mime);
  }

  private void onConnectionFailure(@NonNull String lockKey) {
    Log.d(LOG_TAG, "onConnectionFailure: " + lockKey);
    final HttpURLConnection httpURLConnection = httpURLConnections.remove(lockKey);
    if (httpURLConnection != null) {
      httpURLConnection.disconnect();
    }
    callback.onDisconnected(lockKey);
  }

  public interface Callback {
    void onDisconnected(@NonNull String lockKey);

    void onConnected(@NonNull String lockKey);
  }

  private static class Watchdogs {
    private final ConcurrentHashMap<String, Runnable> watchdogs = new ConcurrentHashMap<>();
    private final android.os.Handler handler = new android.os.Handler(Looper.getMainLooper());

    public void launch(@NonNull Runnable callback, @NonNull String lockKey, int timeoutMs) {
      cancel(lockKey);
      final Runnable watchdog = () -> {
        Log.d(LOG_TAG, "Watchdog fired for " + lockKey);
        watchdogs.remove(lockKey);
        callback.run();
      };
      watchdogs.put(lockKey, watchdog);
      handler.postDelayed(watchdog, timeoutMs * 1000L);
    }

    public void launch(@NonNull Consumer<String> callback, @NonNull String lockKey, int timeoutMs) {
      launch(() -> callback.accept(lockKey), lockKey, timeoutMs);
    }

    public void relaunch(@NonNull String lockKey, int timeoutMs) {
      final Runnable watchdog = watchdogs.get(lockKey);
      if (watchdog != null) {
        handler.removeCallbacks(watchdog);
        handler.postDelayed(watchdog, timeoutMs * 1000L);
      }
    }

    private void cancel(@NonNull String lockKey) {
      final Runnable watchdog = watchdogs.remove(lockKey);
      if (watchdog != null) {
        handler.removeCallbacks(watchdog);
      }
    }
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
        Log.e(LOG_TAG, "LogoHandler: no logo available");
        return;
      }
      Log.d(LOG_TAG, "LogoHandler: serving logo");
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
      final String method = request.getMethod();
      final boolean isGet = method.equals("GET");
      final boolean isHead = method.equals("HEAD");
      Log.d(LOG_TAG, "StreamHandler: " + method + " lockKey = " + incomingLockKey);
      if (!isHead && !isGet || !lockKey.equals(incomingLockKey)) {
        Log.d(LOG_TAG, "StreamHandler: not a valid request - " + method + " - " + incomingLockKey);
        return;
      }
      final int currentSession = session.incrementAndGet();
      final boolean isPcm = request.getPath().endsWith(STREAM_SUFFIX_PCM);
      // Handle response
      if (isPcm) {
        handlePcm(response, responseStream, incomingLockKey, currentSession, isHead);
      } else {
        handleRelay(response, responseStream, incomingLockKey, currentSession, isHead);
      }
    }

    private void onConnected(@NonNull String lockKey, int session) {
      if (isValid(lockKey, session)) {
        Log.d(LOG_TAG, "onConnected: " + lockKey + "/" + session);
        callback.onConnected(lockKey);
      }
    }

    // Only signal disconnect if we're still the active session of lockKey
    private void onDisconnected(@NonNull String lockKey, int session) {
      final boolean isValid = isValid(lockKey, session) || !lockKey.equals(UpnpStreamServer.this.lockKey);
      Log.d(LOG_TAG, "onDisconnected: " + lockKey + "/" + session + " => " + (isValid ? "valid" : "invalid"));
      if (isValid) {
        watchdogs.cancel(lockKey);
        onConnectionFailure(lockKey);
      }
    }

    private boolean isValid(@NonNull String lockKey, int session) {
      return lockKey.equals(UpnpStreamServer.this.lockKey) && (session == UpnpStreamServer.this.session.get());
    }

    private void handlePcm(
      @NonNull HttpServer.Response response,
      @NonNull OutputStream responseStream,
      @NonNull String lockKey,
      int session,
      boolean isHead) throws IOException {
      Log.d(LOG_TAG, "handlePcm: " + lockKey);
      // Send HTTP headers immediately — the renderer must not wait on a cold socket
      response.addHeader(Response.CONTENT_LENGTH, String.valueOf(Long.MAX_VALUE)); // Fake length for streaming WAV
      addDlnaHeaders(response, PCM_MIME, true);
      response.send();
      responseStream.flush();
      if (isHead) {
        return;
      }
      // Wait for onFormatChanged()
      final long deadline = System.currentTimeMillis() + GET_TIMEOUT;
      while (sampleRate == DEFAULT) {
        if (System.currentTimeMillis() > deadline) {
          Log.e(LOG_TAG, "handlePcm: timeout waiting for audio format - " + lockKey);
          onDisconnected(lockKey, session);
          return;
        }
        try {
          //noinspection BusyWait
          Thread.sleep(50);
        } catch (InterruptedException interruptedException) {
          Thread.currentThread().interrupt();
          onDisconnected(lockKey, session);
          return;
        }
      }
      // We can signal actual connection if we're still the active session
      onConnected(lockKey, session);
      // WAV header first, then raw PCM drained from queue
      watchdogs.launch(() -> onDisconnected(lockKey, session), lockKey, LIVELINESS_WATCHDOG_TIMEOUT_S);
      responseStream.write(buildWavHeader(sampleRate, channelCount, bitsPerSample));
      Log.d(LOG_TAG, "handlePcm: renderer started reading PCM stream - " + lockKey);
      while (lockKey.equals(UpnpStreamServer.this.lockKey)) {
        try {
          if (session == UpnpStreamServer.this.session.get()) {
            // Latest: read upstream and forward
            final byte[] pcmData = queue.poll(PACER_POLL_TIMEOUT, TimeUnit.SECONDS);
            if (pcmData == null) {
              Log.w(LOG_TAG, "handlePcm: PCM timeout (" + PACER_POLL_TIMEOUT + "s) - stream stalled - " + lockKey);
              break;
            }
            watchdogs.relaunch(lockKey, LIVELINESS_WATCHDOG_TIMEOUT_S);
            responseStream.write(pcmData);
          } else {
            // Older: stay alive, idle
            //noinspection BusyWait
            Thread.sleep(50);
          }
        } catch (InterruptedException interruptedException) {
          Thread.currentThread().interrupt();
        }
      }
    }

    private void handleRelay(
      @NonNull HttpServer.Response response,
      @NonNull OutputStream responseStream,
      @NonNull String lockKey,
      int session,
      boolean isHead) throws IOException {
      Log.d(LOG_TAG, "handleRelay: " + lockKey);
      // Upstream, shall be already connected in setHttpURLConnectionAndGetContentType
      final HttpURLConnection httpURLConnection = httpURLConnections.get(lockKey);
      if (httpURLConnection == null) {
        return;
      }
      // Send HTTP headers immediately — the renderer must not wait on a cold socket
      String contentType = RadioURL.getStreamContentType(httpURLConnection);
      contentType = (contentType == null) ? DEFAULT_MIME : contentType;
      Log.d(LOG_TAG, "handleRelay: relay mime = " + contentType + " - " + lockKey);
      addDlnaHeaders(response, contentType, false);
      response.send();
      responseStream.flush();
      if (isHead) {
        return;
      }
      // We can signal actual connection if we're still the active session
      onConnected(lockKey, session);
      // Relay
      watchdogs.launch(() -> onDisconnected(lockKey, session), lockKey, LIVELINESS_WATCHDOG_TIMEOUT_S);
      final byte[] buf = new byte[PIPE_BUFFER_SIZE];
      final InputStream src = httpURLConnection.getInputStream();
      Log.d(LOG_TAG, "handleRelay: renderer started reading stream - " + lockKey);
      while (lockKey.equals(UpnpStreamServer.this.lockKey)) {
        try {
          if (session == UpnpStreamServer.this.session.get()) {
            // Latest: read upstream and forward
            final int n = src.read(buf);
            if (n < 0) {
              break;
            }
            watchdogs.relaunch(lockKey, LIVELINESS_WATCHDOG_TIMEOUT_S);
            responseStream.write(buf, 0, n);
          } else {
            // Older: stay alive, idle
            //noinspection BusyWait
            Thread.sleep(50);
          }
        } catch (InterruptedException interruptedException) {
          Thread.currentThread().interrupt();
        }
      }
    }
  }
}