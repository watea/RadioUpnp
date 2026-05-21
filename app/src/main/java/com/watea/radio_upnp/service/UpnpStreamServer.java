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

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.watea.candidhttpserver.HttpServer;
import com.watea.radio_upnp.R;
import com.watea.radio_upnp.model.CapturingAudioSink;
import com.watea.radio_upnp.model.Radio;
import com.watea.radio_upnp.model.RadioURL;
import com.watea.radio_upnp.model.Radios;
import com.watea.radio_upnp.model.RemoteSessionDevice;
import com.watea.radio_upnp.model.UpnpSessionDevice;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UpnpStreamServer extends HttpServer implements RemoteSessionDevice.ServerCallback {
  private static final String LOG_TAG = UpnpStreamServer.class.getSimpleName();
  private static final String STREAM_PATH = "/stream";
  private static final String LOCKKEY_PARAM = "lockkey";
  private static final String ID_PARAM = "id";
  private static final String HEAD = "HEAD";
  private static final String GET = "GET";
  private static final String SCHEME = "http";
  private static final int DEFAULT = -1;
  private static final int GET_TIMEOUT = 10000; // ms
  private static final int QUEUE_SIZE = 300; // ~10s buffer at 48000Hz stereo 16-bit (4608 bytes/chunk)
  private static final int PACER_POLL_TIMEOUT = 500; // ms
  private static final int REMOTE_LOGO_SIZE = 300;
  private static final String LOGO_PATH = "/logo.jpg";
  private static final String STREAM_SUFFIX_PCM = ".wav";
  private static final int PIPE_BUFFER_SIZE = 8192; // Matches default Java I/O buffer size
  private static final int CONNECT_WATCHDOG_TIMEOUT_S = 20;
  private static final int LIVELINESS_WATCHDOG_TIMEOUT_S = 10;
  private static final Pattern PARAM_PATTERN = Pattern.compile("[?&](?:amp;)*([^=]+)=([^&]*)");
  @NonNull
  private final Callback callback;
  @NonNull
  private final Context context;
  @Nullable
  private volatile StreamResource streamResource = null;
  private final CapturingAudioSink.Callback capturingAudioSinkCallback = new CapturingAudioSink.Callback() {
    // Must be called before any PCM streaming is started
    @Override
    public void onFormatChanged(int sampleRate, int channelCount, int bitsPerSample) {
      Log.d(LOG_TAG, "onFormatChanged");
      final StreamResource streamResource = UpnpStreamServer.this.streamResource;
      if (streamResource == null) {
        Log.d(LOG_TAG, "No resource to receive format data");
      } else {
        streamResource.onFormatChanged(sampleRate, channelCount, bitsPerSample);
      }
    }

    @Override
    public void onPcmData(@NonNull byte[] pcmData, @NonNull String lockKey) {
      final StreamResource streamResource = UpnpStreamServer.this.streamResource;
      if (streamResource == null) {
        Log.d(LOG_TAG, "No queue to receive data");
      } else {
        streamResource.onPcmData(pcmData);
      }
    }
  };

  public UpnpStreamServer(@NonNull Context context, @NonNull Callback callback) throws IOException {
    this.context = context;
    this.callback = callback;
    addHandler(new LogoHandler());
    addHandler(new PcmStreamHandler());
    addHandler(new RelayStreamHandler());
  }

  // Fallback for renderers sending HTML-encoded separators such as
  // '&amp;amp;' instead of '&', which confuses request.getParam()
  @Nullable
  private static String getParam(@NonNull HttpServer.Request request, @NonNull String name) {
    final String value = request.getParam(name);
    if (value != null) {
      return value;
    }
    final Matcher matcher = PARAM_PATTERN.matcher(request.getRawPath());
    while (matcher.find()) {
      if (name.equals(matcher.group(1))) {
        return matcher.group(2);
      }
    }
    return null;
  }

  public void release() {
    launch(null);
  }

  @Override
  @NonNull
  public CapturingAudioSink.Callback getPcmCallback() {
    return capturingAudioSinkCallback;
  }

  @Override
  @NonNull
  public Uri getLogoUri(@NonNull Radio radio) {
    return getUriBuilder(radio)
      .path(LOGO_PATH)
      .build();
  }

  @Override
  @NonNull
  public Uri getStreamUri(@NonNull Radio radio, @NonNull String lockKey, boolean isPcm) {
    return getUriBuilder(radio)
      .path(STREAM_PATH + (isPcm ? STREAM_SUFFIX_PCM : ""))
      .appendQueryParameter(LOCKKEY_PARAM, lockKey)
      .build();
  }

  // Must be called early before any session is started.
  // lockKey == null for release.
  public void launch(@Nullable String lockKey) {
    final boolean isRelease = (lockKey == null);
    Log.d(LOG_TAG, "launch: " + (isRelease ? "release" : lockKey));
    streamResource = isRelease ? null : new StreamResource(lockKey);
  }

  public boolean hasLockKey(@Nullable String lockKey) {
    final StreamResource streamResource = this.streamResource;
    return (streamResource != null) && streamResource.lockKey.equals(lockKey);
  }

  @NonNull
  private Uri.Builder getUriBuilder(@NonNull Radio radio) {
    final String localIp = new NetworkProxy(context).getWifiIpAddress();
    return new Uri.Builder()
      .scheme(SCHEME)
      .encodedAuthority(((localIp == null) ? "0.0.0.0" : localIp) + ":" + getListeningPort())
      .appendQueryParameter(ID_PARAM, radio.getId());
  }

  public interface Callback {
    void onDisconnected(@NonNull String lockKey);

    void onConnected(@NonNull String lockKey);

    void onInformation(@NonNull String information, @NonNull String lockKey);
  }

  // Serves the radio logo as JPEG
  private static class LogoHandler implements HttpServer.Handler {
    private static final String LOG_TAG = LogoHandler.class.getSimpleName();

    @Override
    public void handle(
      @NonNull HttpServer.Request request,
      @NonNull HttpServer.Response response,
      @NonNull OutputStream responseStream) throws IOException {
      if (!request.getPath().equals(LOGO_PATH)) {
        return; // Not our path
      }
      final String id = getParam(request, ID_PARAM);
      final Radio radio = (id == null) ? null : Radios.getInstance().getRadioFromId(id);
      if (radio == null) {
        Log.e(LOG_TAG, "handle: no radio available");
        return;
      }
      Log.d(LOG_TAG, "handle: serving logo");
      final Bitmap bitmap = Bitmap.createScaledBitmap(radio.getIcon(), REMOTE_LOGO_SIZE, REMOTE_LOGO_SIZE, true);
      final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
      bitmap.compress(Bitmap.CompressFormat.JPEG, 90, byteArrayOutputStream);
      final byte[] logoBytes = byteArrayOutputStream.toByteArray();
      if (logoBytes.length == 0) {
        Log.e(LOG_TAG, "handle: no logo available");
        return;
      }
      response.addHeader(Response.CONTENT_TYPE, "image/jpeg");
      response.addHeader(Response.CONTENT_LENGTH, String.valueOf(logoBytes.length));
      response.send();
      responseStream.write(logoBytes);
    }
  }

  // Base handler for audio stream requests — validates method and lockKey,
  // dispatches to the concrete subclass only when the path matches
  private abstract class StreamHandler implements HttpServer.Handler {
    @Override
    public final void handle(
      @NonNull HttpServer.Request request,
      @NonNull HttpServer.Response response,
      @NonNull OutputStream responseStream) throws IOException {
      final String incomingLockKey = getParam(request, LOCKKEY_PARAM);
      final String id = getParam(request, ID_PARAM);
      final Radio radio = (id == null) ? null : Radios.getInstance().getRadioFromId(id);
      final String method = request.getMethod();
      final String className = getClass().getSimpleName();
      Log.d(LOG_TAG, className + ": handle - " + method + " - " + id + " - " + incomingLockKey);
      final StreamResource streamResource = UpnpStreamServer.this.streamResource;
      if (streamResource == null) {
        Log.d(LOG_TAG, "handle: no resource defined - " + incomingLockKey);
        return;
      }
      if ((radio != null) && streamResource.hasLockKey(incomingLockKey) && (method.equals(HEAD) || method.equals(GET)) && accept(request.getPath())) {
        final boolean isHead = method.equals(HEAD);
        Log.d(LOG_TAG, className + ": handleStream - " + (isHead ? HEAD : GET) + " - " + incomingLockKey);
        handleStream(response, responseStream, isHead, radio, streamResource);
      }
      Log.d(LOG_TAG, className + ": handle exit - " + method + " - " + incomingLockKey);
    }

    // Returns true if this handler is responsible for the given path
    protected abstract boolean accept(@NonNull String path);

    // Performs the actual streaming for a validated GET/HEAD request
    protected abstract void handleStream(
      @NonNull HttpServer.Response response,
      @NonNull OutputStream responseStream,
      boolean isHead,
      @NonNull Radio radio,
      @NonNull StreamResource streamResource) throws IOException;

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
  }

  private class StreamResource {
    @NonNull
    private final String lockKey;
    private final Set<ArrayBlockingQueue<byte[]>> queues = new CopyOnWriteArraySet<>();
    private volatile Watchdog watchdog;
    // Audio format — set by onFormatChanged() on ExoPlayer thread, read on HTTP server thread.
    private volatile int sampleRate = DEFAULT;
    private volatile int channelCount = DEFAULT;
    private volatile int bitsPerSample = DEFAULT;

    public StreamResource(@NonNull String lockKey) {
      this.lockKey = lockKey;
      watchdog = new Watchdog(callback::onDisconnected, this.lockKey, CONNECT_WATCHDOG_TIMEOUT_S);
    }

    public void onFormatChanged(int sampleRate, int channelCount, int bitsPerSample) {
      this.sampleRate = sampleRate;
      this.channelCount = channelCount;
      this.bitsPerSample = bitsPerSample;
    }

    public int getBitsPerSample() {
      return bitsPerSample;
    }

    public int getChannelCount() {
      return channelCount;
    }

    public int getSampleRate() {
      return sampleRate;
    }

    public void onPcmData(@NonNull byte[] pcmData) {
      if (queues.isEmpty()) {
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

    @NonNull
    public String getLockKey() {
      return lockKey;
    }

    public void relaunchWatchdog() {
      watchdog.relaunch();
    }

    public void onConnected() {
      callback.onConnected(lockKey);
      watchdog.cancel();
      watchdog = new Watchdog(callback::onDisconnected, this.lockKey, LIVELINESS_WATCHDOG_TIMEOUT_S);
    }

    @NonNull
    public ArrayBlockingQueue<byte[]> addQueue() {
      final ArrayBlockingQueue<byte[]> result = new ArrayBlockingQueue<>(QUEUE_SIZE);
      queues.add(result);
      return result;
    }

    public void removeQueue(@NonNull ArrayBlockingQueue<byte[]> queue) {
      queues.remove(queue);
    }

    public boolean hasLockKey(@Nullable String lockKey) {
      return UpnpStreamServer.this.hasLockKey(lockKey);
    }

    public boolean hasLockKey() {
      return hasLockKey(lockKey);
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
      @NonNull StreamResource streamResource) throws IOException {
      // Send HTTP headers immediately — the renderer must not wait on a cold socket
      response.addHeader(Response.CONTENT_LENGTH, String.valueOf(Long.MAX_VALUE)); // Fake length for streaming WAV
      sendDlnaResponse(response, responseStream, UpnpSessionDevice.PCM_MIME, streamResource.getLockKey());
      if (isHead) {
        return;
      }
      // Create queue
      final ArrayBlockingQueue<byte[]> queue = streamResource.addQueue();
      // Wait for onFormatChanged()
      final long deadline = System.currentTimeMillis() + GET_TIMEOUT;
      try {
        while (streamResource.getSampleRate() == DEFAULT) {
          if (System.currentTimeMillis() > deadline) {
            Log.e(LOG_TAG, "PcmStreamHandler: timeout waiting for audio format - " + streamResource.getLockKey());
            callback.onDisconnected(streamResource.getLockKey());
            return;
          }
          try {
            //noinspection BusyWait
            Thread.sleep(50);
          } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            callback.onDisconnected(streamResource.getLockKey());
            return;
          }
        }
        // We signal actual connection and start stream
        streamResource.onConnected();
        responseStream.write(buildWavHeader(streamResource.getSampleRate(), streamResource.getChannelCount(), streamResource.getBitsPerSample()));
        Log.d(LOG_TAG, "PcmStreamHandler: start streaming - " + streamResource.getLockKey());
        try {
          while (streamResource.hasLockKey()) {
            final byte[] pcmData = queue.poll(PACER_POLL_TIMEOUT, TimeUnit.MILLISECONDS);
            if (pcmData == null) {
              Log.d(LOG_TAG, "PcmStreamHandler: pcmData is null");
            } else {
              streamResource.relaunchWatchdog();
              responseStream.write(pcmData);
            }
          }
        } catch (InterruptedException interruptedException) {
          Thread.currentThread().interrupt();
        }
      } catch (IOException ioException) {
        Log.d(LOG_TAG, "PcmStreamHandler: IOException - " + streamResource.getLockKey() + "; " + ioException.getMessage());
        throw ioException;
      } finally {
        streamResource.removeQueue(queue);
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
      @NonNull StreamResource streamResource) throws IOException {
      // Upstream
      final Radio.ConnectionSet connectionSet = radio.getConnectionSet(context.getString(R.string.app_name));
      if (connectionSet == null) {
        Log.d(LOG_TAG, "RelayStreamHandler: upstream is not defined");
        callback.onDisconnected(streamResource.getLockKey());
        return;
      }
      // Send HTTP headers immediately — the renderer must not wait on a cold socket
      sendDlnaResponse(response, responseStream, connectionSet.getContent(), streamResource.getLockKey());
      if (isHead) {
        return;
      }
      // New upstream connection per GET thread — a shared connection causes concurrent stream corruption
      final HttpURLConnection httpURLConnection;
      try {
        httpURLConnection = new RadioURL(connectionSet.getUrl()).getActualHttpURLConnection(context.getString(R.string.app_name));
      } catch (IOException ioException) {
        Log.d(LOG_TAG, "RelayStreamHandler: unable to connect", ioException);
        callback.onDisconnected(streamResource.getLockKey());
        throw ioException;
      }
      // We signal actual connection and start stream
      streamResource.onConnected();
      final String icyMetaIntValue = httpURLConnection.getHeaderField("Icy-Metaint");
      final IcyStreamParser parser = (icyMetaIntValue == null) ? null :
        new IcyStreamParser(Integer.parseInt(icyMetaIntValue), title -> callback.onInformation(title, streamResource.getLockKey()));
      final byte[] buf = new byte[PIPE_BUFFER_SIZE];
      int n;
      Log.d(LOG_TAG, "RelayStreamHandler: start streaming - " + streamResource.getLockKey());
      try (final InputStream inputStream = httpURLConnection.getInputStream()) {
        while (streamResource.hasLockKey() && ((n = inputStream.read(buf)) >= 0)) {
          streamResource.relaunchWatchdog();
          if (parser == null) {
            responseStream.write(buf, 0, n);
          } else {
            responseStream.write(parser.parse(buf, n));
          }
        }
      } catch (IOException ioException) {
        Log.d(LOG_TAG, "RelayStreamHandler: IOException - " + streamResource.getLockKey() + "; " + ioException.getMessage());
        throw ioException;
      } finally {
        httpURLConnection.disconnect();
      }
    }
  }
}