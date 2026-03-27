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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
  private static final String STREAM_SUFFIX_PCM = ".wav";
  private static final String STREAM_SUFFIX_RELAY = ".audio"; // neutral extension
  private static final int PIPE_BUFFER_SIZE = 8192;
  private static final Pattern STREAM_PATH_PATTERN = Pattern.compile(
    "^/" + STREAM_PREFIX + "([^/]+?)(?:" +
      Pattern.quote(STREAM_SUFFIX_PCM) + "|" +
      Pattern.quote(STREAM_SUFFIX_RELAY) + ")?$");
  private static final int RELAY_WATCHDOG_TIMEOUT_S = 10;
  @NonNull
  private final Callback callback;
  private final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(QUEUE_SIZE);
  private final ConcurrentHashMap<String, Runnable> relayWatchdogs = new ConcurrentHashMap<>();
  private final android.os.Handler handler = new android.os.Handler(Looper.getMainLooper());
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
    // Arm watchdog for this lockKey — relay must connect within timeout
    final String watchedLockKey = lockKey;
    final Runnable watchdog = () -> {
      relayWatchdogs.remove(watchedLockKey);
      Log.e(TAG, "setRelayUrl: watchdog fired for " + watchedLockKey);
      callback.onDisconnect(watchedLockKey);
    };
    relayWatchdogs.put(watchedLockKey, watchdog);
    handler.postDelayed(watchdog, RELAY_WATCHDOG_TIMEOUT_S * 1000L);
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

  // Returns stream URI adapted to current mode (PCM vs relay)
  public Uri getStreamUri(@NonNull String localIp, @NonNull String lockKey, boolean isPcm) {
    final String suffix = isPcm ? STREAM_SUFFIX_PCM : STREAM_SUFFIX_RELAY;
    return Uri.parse(SCHEME + localIp + ":" + getListeningPort() + "/" + STREAM_PREFIX + lockKey + suffix);
  }

  private void cancelRelayWatchdog(@NonNull String lockKey) {
    final Runnable watchdog = relayWatchdogs.remove(lockKey);
    if (watchdog != null) {
      handler.removeCallbacks(watchdog);
    }
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
    private final ConcurrentHashMap<String, AtomicInteger> activeConnections = new ConcurrentHashMap<>();

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
      if (relayUrl == null) {
        handlePcm(response, responseStream, incomingLockKey, isHead);
      } else {
        handleRelay(response, responseStream, incomingLockKey, isHead);
      }
    }

    private void register(@NonNull String lockKey) {
      Log.d(TAG, "register: " + lockKey);
      cancelRelayWatchdog(lockKey);
      activeConnections.computeIfAbsent(lockKey, k -> new AtomicInteger(0)).incrementAndGet();
    }

    private boolean unRegister(@NonNull String lockKey) {
      Log.d(TAG, "unRegister: " + lockKey);
      final AtomicInteger counter = activeConnections.get(lockKey);
      if ((counter != null) && (counter.decrementAndGet() == 0)) {
        activeConnections.remove(lockKey);
        return true;
      }
      return false;
    }

    private void connect(@NonNull String lockKey) {
      Log.d(TAG, "connect: " + lockKey);
      callback.onConnected(lockKey);
    }

    private void disconnect(@NonNull String lockKey) {
      if (unRegister(lockKey)) {
        Log.d(TAG, "disconnect: " + lockKey);
        callback.onDisconnect(lockKey);
      } else {
        Log.d(TAG, "disconnect: others still active for " + lockKey);
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
      Log.d(TAG, "handlePcm: renderer started reading PCM stream");
      // We can signal actual connection
      register(lockKey);
      connect(lockKey);
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
        disconnect(lockKey);
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
      // Log request
      register(lockKey);
      // Connect to upstream
      HttpURLConnection httpURLConnection;
      try {
        httpURLConnection = new RadioURL(relayUrl)
          .getActualHttpURLConnection(conn -> conn.setRequestProperty("Icy-MetaData", "0")); // ExoPlayer handles ICY locally
      } catch (IOException ioException) {
        Log.d(TAG, "handleRelay: unable to connect", ioException);
        disconnect(lockKey);
        return;
      }
      final String contentType = RadioURL.getStreamContentType(httpURLConnection);
      final String mime = (contentType != null) ? contentType : DEFAULT_MIME;
      Log.d(TAG, "handleRelay: relay mime = " + mime);
      addDlnaHeaders(response, mime);
      response.send(); // Headers on the wire — body follows immediately
      if (isHead) {
        httpURLConnection.disconnect();
        unRegister(lockKey);
        return;
      }
      // We can signal actual connection
      connect(lockKey);
      try {
        final byte[] buf = new byte[PIPE_BUFFER_SIZE];
        int n;
        try (final InputStream src = httpURLConnection.getInputStream()) {
          while ((n = src.read(buf)) != -1) {
            if (!lockKey.equals(callback.getLockKey())) {
              Log.d(TAG, "pipe: stream stopped");
              break;
            }
            responseStream.write(buf, 0, n);
          }
        }
      } finally {
        httpURLConnection.disconnect();
        disconnect(lockKey);
      }
    }
  }
}