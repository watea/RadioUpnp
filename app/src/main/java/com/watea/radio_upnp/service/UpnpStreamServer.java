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

import com.watea.candidhttpserver.HttpServer;
import com.watea.radio_upnp.model.CapturingAudioSink;
import com.watea.radio_upnp.model.Radio;
import com.watea.radio_upnp.model.RadioURL;
import com.watea.radio_upnp.model.Radios;
import com.watea.radio_upnp.model.UpnpSessionDevice;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeUnit;

public class UpnpStreamServer extends HttpServer {
  private static final String LOG_TAG = UpnpStreamServer.class.getSimpleName();
  private static final String STREAM_PATH = "/stream";
  private static final String LOCKKEY_PARAM = "lockkey";
  private static final String ID_PARAM = "id";
  private static final String ICY_METADATA_HEADER = "icy-metadata";
  private static final String ICY_METAINT_HEADER = "icy-metaint";
  private static final String HEAD = "HEAD";
  private static final String GET = "GET";
  private static final String SCHEME = "http";
  private static final int DEFAULT = -1;
  private static final int GET_TIMEOUT = 10000; // ms
  private static final int QUEUE_SIZE = 300; // ~10s buffer at 48000Hz stereo 16-bit (4608 bytes/chunk)
  private static final int PACER_POLL_TIMEOUT = 500; // ms
  private static final int REMOTE_LOGO_SIZE = 300;
  private static final String LOGO_PATH = "/logo";
  private static final String LOGO_SUFFIX = ".jpg";
  private static final String STREAM_SUFFIX_PCM = ".wav";
  private static final int PIPE_BUFFER_SIZE = 8192; // Matches default Java I/O buffer size
  private static final int CONNECT_WATCHDOG_TIMEOUT_S = 20;
  private static final int LIVELINESS_WATCHDOG_TIMEOUT_S = 10;
  @NonNull
  private final Callback callback;
  private final ConcurrentHashMap<String, Watchdog> watchdogs = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Set<ArrayBlockingQueue<byte[]>>> queuess = new ConcurrentHashMap<>();
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
      final Set<ArrayBlockingQueue<byte[]>> queues = queuess.get(lockKey);
      if ((queues == null) || (queues.isEmpty())) {
        Log.d(LOG_TAG, "No queue to receive data");
        return;
      }
      queues.forEach(queue -> {
        final int remaining = queue.remainingCapacity();
        if (remaining < QUEUE_SIZE * 0.2F) {
          Log.w(LOG_TAG, "Queue fill: " + (QUEUE_SIZE - remaining) + "/" + QUEUE_SIZE);
        }
        if (!queue.offer(pcmData)) {
          Log.e(LOG_TAG, "QUEUE FULL => DROP (" + pcmData.length + " bytes)");
          queues.remove(queue);
        }
      });
    }
  };
  @Nullable
  private Uri logoUri = null;
  @Nullable
  private byte[] logoBytes = null;

  public UpnpStreamServer(@NonNull Callback callback) throws IOException {
    this.callback = callback;
    addHandler(new LogoHandler());
    addHandler(new PcmStreamHandler());
    addHandler(new RelayStreamHandler());
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
    return logoUri = new Uri.Builder()
      .scheme(SCHEME)
      .encodedAuthority(localIp + ":" + getListeningPort())
      .path(LOGO_PATH + radio.getId() + LOGO_SUFFIX)
      .build();
  }

  public void release() {
    launch(null);
  }

  public Uri getStreamUri(@NonNull String localIp, @NonNull Radio radio, @NonNull String lockKey, boolean isPcm) {
    return new Uri.Builder()
      .scheme(SCHEME)
      .encodedAuthority(localIp + ":" + getListeningPort())
      .path(STREAM_PATH + (isPcm ? STREAM_SUFFIX_PCM : ""))
      .appendQueryParameter(ID_PARAM, radio.getId())
      .appendQueryParameter(LOCKKEY_PARAM, lockKey)
      .build();
  }

  // Must be called early before any session is started.
  // lockKey == null for release.
  public void launch(@Nullable String lockKey) {
    // isRelease?
    final boolean isRelease = (lockKey == null);
    Log.d(LOG_TAG, "setLockKey: " + (isRelease ? "release" : lockKey));
    this.lockKey = isRelease ? RadioService.getLockKey() : lockKey;
    // Create new queue set
    queuess.clear();
    // Update lockKey
    sampleRate = DEFAULT;
    // Watchdog
    watchdogs.clear();
    // Launch
    if (!isRelease) {
      queuess.put(lockKey, new CopyOnWriteArraySet<>());
      watchdogs.put(lockKey, new Watchdog(callback::onDisconnected, lockKey, CONNECT_WATCHDOG_TIMEOUT_S));
    }
  }

  public interface Callback {
    void onDisconnected(@NonNull String lockKey);

    void onConnected(@NonNull String lockKey);

    void onInformation(@NonNull String information, @NonNull String lockKey);
  }

  // Base handler for audio stream requests — validates method and lockKey,
  // dispatches to the concrete subclass only when the path matches
  private abstract class StreamHandler implements HttpServer.Handler {
    @Override
    public final void handle(
      @NonNull HttpServer.Request request,
      @NonNull HttpServer.Response response,
      @NonNull OutputStream responseStream) throws IOException {
      final String incomingLockKey = request.getParams(LOCKKEY_PARAM);
      final String id = request.getParams(ID_PARAM);
      final Radio radio = (id == null) ? null : Radios.getInstance().getRadioFromId(id);
      final String method = request.getMethod();
      Log.d(LOG_TAG, getClass().getSimpleName() + ": handle - " + method + " - " + id + " -" + incomingLockKey);
      if ((radio != null) && lockKey.equals(incomingLockKey) && (method.equals(HEAD) || method.equals(GET)) && accept(request.getPath())) {
        handleStream(response, responseStream, method.equals(HEAD), radio, lockKey);
      }
      Log.d(LOG_TAG, getClass().getSimpleName() + ": handle exit - " + method + " - " + incomingLockKey);
    }

    // Returns true if this handler is responsible for the given path
    protected abstract boolean accept(@NonNull String path);

    // Performs the actual streaming for a validated GET/HEAD request
    protected abstract void handleStream(
      @NonNull HttpServer.Response response,
      @NonNull OutputStream responseStream,
      boolean isHead,
      @NonNull Radio radio,
      @NonNull String lockKey) throws IOException;

    // Adds DLNA streaming headers common to both PCM and relay responses
    protected void sendDlnaResponse(
      @NonNull HttpServer.Response response,
      @NonNull OutputStream responseStream,
      @NonNull String mime,
      @NonNull String lockKey) throws IOException {
      response.addHeader("transferMode.dlna.org", "Streaming");
      response.addHeader("contentFeatures.dlna.org", UpnpSessionDevice.getDlnaTail(mime));
      response.addHeader(Response.CONTENT_TYPE, mime);
      try {
        response.send();
        responseStream.flush();
      } catch (IOException ioException) {
        Log.d(LOG_TAG, "sendDlnaResponse: IOException - " + lockKey + "; " + ioException.getMessage());
        callback.onDisconnected(lockKey);
        throw ioException;
      }
    }

    protected void signalConnectionAndLaunchWatchdog(@NonNull String lockKey) {
      callback.onConnected(lockKey);
      watchdogs.computeIfPresent(lockKey, (k, v) -> {
        v.cancel();
        return v;
      });
      watchdogs.put(lockKey, new Watchdog(callback::onDisconnected, lockKey, LIVELINESS_WATCHDOG_TIMEOUT_S));
    }

    protected void relaunchWatchdog(@NonNull String lockKey) {
      watchdogs.computeIfPresent(lockKey, (k, v) -> {
        v.relaunch();
        return v;
      });
    }
  }

  // Serves the radio logo as JPEG
  private class LogoHandler implements HttpServer.Handler {
    @Override
    public void handle(
      @NonNull HttpServer.Request request,
      @NonNull HttpServer.Response response,
      @NonNull OutputStream responseStream) throws IOException {
      if ((logoUri == null) || !request.getPath().equals(logoUri.getPath())) {
        return; // Not our path
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

  // Serves the audio stream in PCM/WAV mode
  private class PcmStreamHandler extends StreamHandler {
    @Override
    protected boolean accept(@NonNull String path) {
      return path.endsWith(STREAM_SUFFIX_PCM);
    }

    @Override
    protected void handleStream(
      @NonNull HttpServer.Response response,
      @NonNull OutputStream responseStream,
      boolean isHead,
      @NonNull Radio radio,
      @NonNull String lockKey) throws IOException {
      Log.d(LOG_TAG, getClass().getSimpleName() + ": handleStream - " + (isHead ? HEAD : GET) + " - " + lockKey);
      // Send HTTP headers immediately — the renderer must not wait on a cold socket
      response.addHeader(Response.CONTENT_LENGTH, String.valueOf(Long.MAX_VALUE)); // Fake length for streaming WAV
      sendDlnaResponse(response, responseStream, UpnpSessionDevice.PCM_MIME, lockKey);
      if (isHead) {
        return;
      }
      // Create queue
      final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(QUEUE_SIZE);
      final Set<ArrayBlockingQueue<byte[]>> queues = queuess.get(lockKey);
      if (queues == null) {
        Log.e(LOG_TAG, "PcmStreamHandler: no queue available - " + lockKey);
        callback.onDisconnected(lockKey);
        return;
      } else {
        queues.add(queue);
      }
      // Wait for onFormatChanged()
      final long deadline = System.currentTimeMillis() + GET_TIMEOUT;
      try {
        while (sampleRate == DEFAULT) {
          if (System.currentTimeMillis() > deadline) {
            Log.e(LOG_TAG, "PcmStreamHandler: timeout waiting for audio format - " + lockKey);
            callback.onDisconnected(lockKey);
            return;
          }
          try {
            //noinspection BusyWait
            Thread.sleep(50);
          } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            callback.onDisconnected(lockKey);
            return;
          }
        }
        // We signal actual connection and start stream
        signalConnectionAndLaunchWatchdog(lockKey);
        responseStream.write(buildWavHeader(sampleRate, channelCount, bitsPerSample));
        Log.d(LOG_TAG, "PcmStreamHandler: start streaming - " + lockKey);
        try {
          while (lockKey.equals(UpnpStreamServer.this.lockKey)) {
            final byte[] pcmData = queue.poll(PACER_POLL_TIMEOUT, TimeUnit.MILLISECONDS);
            if (pcmData == null) {
              Log.d(LOG_TAG, "PcmStreamHandler: pcmData is null");
            } else {
              relaunchWatchdog(lockKey);
              responseStream.write(pcmData);
            }
          }
        } catch (InterruptedException interruptedException) {
          Thread.currentThread().interrupt();
        }
      } catch (IOException ioException) {
        Log.d(LOG_TAG, "PcmStreamHandler: IOException - " + lockKey + "; " + ioException.getMessage());
        throw ioException;
      } finally {
        queues.remove(queue);
      }
    }

    // Builds a standard 44-byte WAV header.
    // Size fields are set to 0xFFFFFFFF to indicate an unbounded stream,
    // which is the common practice for HTTP audio streaming.
    @NonNull
    private byte[] buildWavHeader(int sampleRate, int channelCount, int bitsPerSample) {
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
  }

  // Serves the audio stream in relay (passthrough) mode
  private class RelayStreamHandler extends StreamHandler {
    @Override
    protected boolean accept(@NonNull String path) {
      return !path.endsWith(STREAM_SUFFIX_PCM);
    }

    @Override
    protected void handleStream(
      @NonNull HttpServer.Response response,
      @NonNull OutputStream responseStream,
      boolean isHead,
      @NonNull Radio radio,
      @NonNull String lockKey) throws IOException {
      Log.d(LOG_TAG, getClass().getSimpleName() + ": handleStream - " + (isHead ? HEAD : GET) + " - " + lockKey);
      // Upstream
      final Radio.ConnectionSet connectionSet = radio.getConnectionSet();
      if (connectionSet == null) {
        Log.d(LOG_TAG, "RelayStreamHandler: upstream is not defined");
        callback.onDisconnected(lockKey);
        return;
      }
      // Send HTTP headers immediately — the renderer must not wait on a cold socket
      sendDlnaResponse(response, responseStream, connectionSet.getContent(), lockKey);
      if (isHead) {
        return;
      }
      // New upstream connection per GET thread — a shared connection causes concurrent stream corruption
      final HttpURLConnection httpURLConnection;
      try {
        httpURLConnection = new RadioURL(connectionSet.getUrl()).getActualHttpURLConnection(Collections.singletonMap(ICY_METADATA_HEADER, "1"));
      } catch (IOException ioException) {
        Log.d(LOG_TAG, "RelayStreamHandler: unable to connect", ioException);
        callback.onDisconnected(lockKey);
        throw ioException;
      }
      // We signal actual connection and start stream
      signalConnectionAndLaunchWatchdog(lockKey);
      final String icyMetaIntValue = httpURLConnection.getHeaderField(ICY_METAINT_HEADER);
      final IcyStreamParser parser = (icyMetaIntValue == null) ? null :
        new IcyStreamParser(Integer.parseInt(icyMetaIntValue), title -> callback.onInformation(title, lockKey));
      final byte[] buf = new byte[PIPE_BUFFER_SIZE];
      int n;
      Log.d(LOG_TAG, "RelayStreamHandler: start streaming - " + lockKey);
      try (final InputStream inputStream = httpURLConnection.getInputStream()) {
        while (lockKey.equals(UpnpStreamServer.this.lockKey) && ((n = inputStream.read(buf)) >= 0)) {
          relaunchWatchdog(lockKey);
          if (parser == null) {
            responseStream.write(buf, 0, n);
          } else {
            responseStream.write(parser.parse(buf, n));
          }
        }
      } catch (IOException ioException) {
        Log.d(LOG_TAG, "RelayStreamHandler: IOException - " + lockKey + "; " + ioException.getMessage());
        throw ioException;
      } finally {
        httpURLConnection.disconnect();
      }
    }
  }
}