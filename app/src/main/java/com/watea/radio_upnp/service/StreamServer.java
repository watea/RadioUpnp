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
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.watea.candidhttpserver.HttpServer;
import com.watea.radio_upnp.model.Radio;
import com.watea.radio_upnp.model.RadioURL;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class StreamServer extends HttpServer {
  private static final String LOG_TAG = StreamServer.class.getSimpleName();
  private static final String STREAM_PATH = "/stream";
  private static final String LOCKKEY_PARAM = "lockkey";
  private static final String SCHEME = "http";
  private static final int DEFAULT = -1;
  private static final int QUEUE_SIZE = 300; // ~10s buffer at 48000Hz stereo 16-bit (4608 bytes/chunk)
  private static final String LOGO_PATH = "/logo.jpg";
  private static final String STREAM_SUFFIX_PCM = ".wav";
  private static final Pattern PARAM_PATTERN = Pattern.compile("[?&](?:amp;)*([^=]+)=([^&]*)");
  @NonNull
  private final Context context;
  @Nullable
  private volatile StreamContext streamContext = null;

  public StreamServer(@NonNull Context context) throws IOException {
    this.context = context;
    addHandler(new LogoHandler());
    addHandler(new PcmStreamHandler());
    addHandler(new PassthroughStreamHandler());
  }

  // Fallback for renderers sending HTML-encoded separators such as
  // '&amp;amp;' instead of '&', which confuses request.getParam()
  @Nullable
  private static String getParam(@NonNull HttpServer.Request request) {
    final String value = request.getParam(LOCKKEY_PARAM);
    if (value != null) {
      return value;
    }
    final Matcher matcher = PARAM_PATTERN.matcher(request.getRawPath());
    while (matcher.find()) {
      if (LOCKKEY_PARAM.equals(matcher.group(1))) {
        return matcher.group(2);
      }
    }
    return null;
  }

  public void onPcmMime(@NonNull String mime, @NonNull String lockKey) {
    final StreamContext streamContext = this.streamContext;
    if (streamContext != null) {
      streamContext.onPcmMime(mime, lockKey);
    }
  }

  @NonNull
  public Uri getLogoUri(@NonNull String lockKey) {
    return getUriBuilder(lockKey).path(LOGO_PATH).build();
  }

  @NonNull
  public Uri getStreamUri(@NonNull String lockKey, boolean isPcm) {
    return getUriBuilder(lockKey).path(STREAM_PATH + (isPcm ? STREAM_SUFFIX_PCM : "")).build();
  }

  public void release() {
    Log.d(LOG_TAG, "release");
    streamContext = null;
  }

  // Must be called early before any session is started.
  // Returns the CapturingAudioSink.Callback to wire directly to that session's own
  // CapturingAudioSink — each session's sink talks only to its own StreamContext.
  @NonNull
  public CapturingAudioSink.Callback launch(@NonNull Radio radio, @NonNull Listener listener, @NonNull String lockKey) {
    Log.d(LOG_TAG, "launch: " + lockKey);
    final StreamContext newStreamContext = new StreamContext(radio, listener, lockKey);
    streamContext = newStreamContext;
    return newStreamContext;
  }

  @NonNull
  private Uri.Builder getUriBuilder(@NonNull String lockKey) {
    final String localIp = new NetworkProxy(context).getWifiIpAddress();
    return new Uri.Builder()
      .scheme(SCHEME)
      .encodedAuthority(((localIp == null) ? "0.0.0.0" : localIp) + ":" + getListeningPort())
      .appendQueryParameter(LOCKKEY_PARAM, lockKey);
  }

  public interface Listener {
    void onDisconnected(@NonNull String lockKey);

    void onConnected(@NonNull String lockKey);

    void onNewInformation(@NonNull String information, @NonNull String lockKey);

    default void onPcmFormat(int sampleRate, int channelCount, int bitsPerSample) {
    }
  }

  private static class Watchdog {
    private static final String LOG_TAG = Watchdog.class.getSimpleName();
    private static final android.os.Handler HANDLER = new android.os.Handler(Looper.getMainLooper());
    @NonNull
    private final Runnable runnable;
    private final int timeoutS;

    public Watchdog(@NonNull Consumer<String> consumer, @NonNull String lockKey, int timeoutS) {
      this.timeoutS = timeoutS;
      runnable = () -> {
        Log.d(LOG_TAG, "Watchdog fired for " + lockKey);
        consumer.accept(lockKey);
      };
      launch();
    }

    public void relaunch() {
      cancel();
      launch();
    }

    public void cancel() {
      HANDLER.removeCallbacks(runnable);
    }

    private void launch() {
      HANDLER.postDelayed(runnable, timeoutS * 1000L);
    }
  }

  // Base handler for audio stream requests — validates method and lockKey,
  // dispatches to the concrete subclass only when the path matches
  private abstract class BaseStreamHandler implements HttpServer.Handler {
    @Override
    public final void handle(
      @NonNull HttpServer.Request request,
      @NonNull HttpServer.Response response,
      @NonNull OutputStream responseStream) throws IOException {
      final String className = getClass().getSimpleName();
      final String incomingLockKey = getParam(request);
      final String method = request.getMethod();
      Log.d(LOG_TAG, className + ": handle - " + method + " - " + incomingLockKey);
      final StreamContext streamContext = StreamServer.this.streamContext;
      if (streamContext == null) {
        Log.d(LOG_TAG, className + ": handle => no resource defined - " + incomingLockKey);
        return;
      }
      final boolean isHead = "HEAD".equals(method);
      final boolean isGet = "GET".equals(method);
      if (streamContext.hasLockKey(incomingLockKey) && (isHead || isGet) && accept(request.getPath())) {
        Log.d(LOG_TAG, className + ": handle valid");
        handleStream(response, responseStream, isHead, streamContext);
      }
      Log.d(LOG_TAG, className + ": handle exit - " + method + " - " + incomingLockKey);
    }

    // Returns true if this handler is responsible for the given path
    protected abstract boolean accept(@NonNull String path);

    protected abstract void handleStream(
      @NonNull HttpServer.Response response,
      @NonNull OutputStream responseStream,
      boolean isHead,
      @NonNull StreamContext streamContext) throws IOException;

    // Send HTTP headers immediately — the renderer must not wait on a cold socket
    protected void sendDlnaResponse(
      @NonNull HttpServer.Response response,
      @NonNull OutputStream responseStream,
      @NonNull String mime,
      @NonNull StreamContext streamContext) throws IOException {
      response.addHeader("transferMode.dlna.org", "Streaming");
      response.addHeader("contentFeatures.dlna.org", UpnpSessionDevice.getDlnaTail(mime));
      response.addHeader(Response.CONTENT_TYPE, mime);
      // Body length is unbounded and unknown upfront; the socket is closed when the
      // stream ends, so make that explicit instead of leaving HTTP/1.1 framing ambiguous
      // (no Content-Length can correctly describe a live stream, and there is no chunked
      // transfer-encoding support in HttpServer).
      response.addHeader("Connection", "close");
      try {
        response.send();
        responseStream.flush();
      } catch (IOException ioException) {
        Log.d(LOG_TAG, "sendDlnaResponse: IOException - " + streamContext.getLockKey() + "; " + ioException.getMessage());
        streamContext.onDisconnected();
        throw ioException;
      }
    }
  }

  private class StreamContext implements CapturingAudioSink.Callback {
    private static final int CONNECT_WATCHDOG_TIMEOUT_S = 20;
    private static final int LIVELINESS_WATCHDOG_TIMEOUT_S = 10;
    @NonNull
    private final Radio radio;
    @NonNull
    private final Listener listener;
    @NonNull
    private final String lockKey;
    private final Set<ArrayBlockingQueue<byte[]>> queues = new CopyOnWriteArraySet<>();
    @NonNull
    private volatile Watchdog watchdog;
    // Audio format — set by onFormatChanged() on ExoPlayer thread, read on HTTP server thread.
    private volatile int sampleRate = DEFAULT;
    private volatile int channelCount = DEFAULT;
    private volatile int bitsPerSample = DEFAULT;
    @NonNull
    private volatile String pcmMime = UpnpSessionDevice.PCM_MIME;

    public StreamContext(@NonNull Radio radio, @NonNull Listener listener, @NonNull String lockKey) {
      this.radio = radio;
      this.listener = listener;
      this.lockKey = lockKey;
      watchdog = new Watchdog(listener::onDisconnected, this.lockKey, CONNECT_WATCHDOG_TIMEOUT_S);
    }

    @NonNull
    public Radio getRadio() {
      return radio;
    }

    @Override
    public void onFormatChanged(int sampleRate, int channelCount, int bitsPerSample) {
      // WAV header / L16 MIME are sent once: the renderer keeps decoding with the initial format
      if ((this.sampleRate != DEFAULT) &&
        ((this.sampleRate != sampleRate) || (this.channelCount != channelCount) || (this.bitsPerSample != bitsPerSample))) {
        Log.w(LOG_TAG, "onFormatChanged: PCM format changed mid-stream from " +
          this.sampleRate + "/" + this.channelCount + "/" + this.bitsPerSample + " to " +
          sampleRate + "/" + channelCount + "/" + bitsPerSample);
      }
      this.sampleRate = sampleRate;
      this.channelCount = channelCount;
      this.bitsPerSample = bitsPerSample;
      listener.onPcmFormat(sampleRate, channelCount, bitsPerSample);
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

    public void onPcmMime(@NonNull String pcmMime, @NonNull String lockKey) {
      if (hasLockKey(lockKey)) {
        this.pcmMime = pcmMime;
      }
    }

    @NonNull
    public String getPcmMime() {
      return pcmMime;
    }

    @Override
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
      listener.onConnected(lockKey);
      watchdog.cancel();
      watchdog = new Watchdog(listener::onDisconnected, lockKey, LIVELINESS_WATCHDOG_TIMEOUT_S);
    }

    public void onDisconnected() {
      listener.onDisconnected(lockKey);
    }

    public void onNewInformation(@NonNull String information) {
      listener.onNewInformation(information, lockKey);
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
      return this.lockKey.equals(lockKey);
    }

    public boolean hasLockKey() {
      return (this == streamContext);
    }
  }

  // Serves the radio logo as JPEG
  private class LogoHandler extends BaseStreamHandler {
    @Override
    protected boolean accept(@NonNull String path) {
      return path.equals(LOGO_PATH);
    }

    @Override
    protected void handleStream(
      @NonNull HttpServer.Response response,
      @NonNull OutputStream responseStream,
      boolean isHead,
      @NonNull StreamContext streamContext) throws IOException {
      // Should not happen
      if (isHead) {
        return;
      }
      Log.d(LOG_TAG, "Serving logo");
      final byte[] logoBytes = streamContext.getRadio().iconToBytes(Bitmap.CompressFormat.JPEG, 90);
      if (logoBytes.length == 0) {
        Log.e(LOG_TAG, "No logo available");
        return;
      }
      response.addHeader(Response.CONTENT_TYPE, "image/jpeg");
      response.addHeader(Response.CONTENT_LENGTH, String.valueOf(logoBytes.length));
      response.send();
      responseStream.write(logoBytes);
    }
  }

  // Serves the audio stream in PCM/WAV mode
  private class PcmStreamHandler extends BaseStreamHandler {
    private static final int GET_TIMEOUT = 10000; // ms
    private static final int PACER_POLL_TIMEOUT = 500; // ms

    @Override
    protected boolean accept(@NonNull String path) {
      return path.endsWith(STREAM_SUFFIX_PCM);
    }

    @Override
    protected void handleStream(
      @NonNull HttpServer.Response response,
      @NonNull OutputStream responseStream,
      boolean isHead,
      @NonNull StreamContext streamContext) throws IOException {
      // HEAD
      response.addHeader(Response.CONTENT_LENGTH, String.valueOf(Long.MAX_VALUE)); // Fake length for streaming PCM
      sendDlnaResponse(response, responseStream, streamContext.getPcmMime(), streamContext);
      if (isHead) {
        return;
      }
      // Create queue
      final ArrayBlockingQueue<byte[]> queue = streamContext.addQueue();
      // Wait for onFormatChanged()
      final long deadline = System.currentTimeMillis() + GET_TIMEOUT;
      try {
        while (streamContext.getSampleRate() == DEFAULT) {
          if (System.currentTimeMillis() > deadline) {
            Log.e(LOG_TAG, "PcmStreamHandler: timeout waiting for audio format - " + streamContext.getLockKey());
            streamContext.onDisconnected();
            return;
          }
          try {
            //noinspection BusyWait
            Thread.sleep(50);
          } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            streamContext.onDisconnected();
            return;
          }
        }
        // We signal actual connection and start stream
        streamContext.onConnected();
        final boolean isRawPcm = UpnpSessionDevice.isRawPcm(streamContext.getPcmMime());
        if (!isRawPcm) {
          responseStream.write(buildWavHeader(streamContext.getSampleRate(), streamContext.getChannelCount(), streamContext.getBitsPerSample()));
        }
        Log.d(LOG_TAG, "PcmStreamHandler: start streaming - " + streamContext.getLockKey());
        try {
          while (streamContext.hasLockKey()) {
            final byte[] pcmData = queue.poll(PACER_POLL_TIMEOUT, TimeUnit.MILLISECONDS);
            if (pcmData == null) {
              Log.d(LOG_TAG, "PcmStreamHandler: pcmData is null");
            } else {
              streamContext.relaunchWatchdog();
              responseStream.write(isRawPcm ? toBigEndian16(pcmData) : pcmData);
            }
          }
        } catch (InterruptedException interruptedException) {
          Thread.currentThread().interrupt();
        }
      } catch (IOException ioException) {
        Log.d(LOG_TAG, "PcmStreamHandler: IOException - " + streamContext.getLockKey() + "; " + ioException.getMessage());
        throw ioException;
      } finally {
        streamContext.removeQueue(queue);
      }
    }

    // Builds a standard 44-byte WAV header.
    // Size fields are set to 0x7FFFFFFF (max signed 32-bit value) to indicate an
    // unbounded stream — 0xFFFFFFFF is the bit pattern for -1 as a signed int and
    // can be rejected by strict parsers that read these fields as signed.
    @NonNull
    private byte[] buildWavHeader(int sampleRate, int channelCount, int bitsPerSample) {
      final int byteRate = sampleRate * channelCount * (bitsPerSample / 8);
      final int blockAlign = channelCount * (bitsPerSample / 8);
      final ByteBuffer buf = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
      buf.put(new byte[]{'R', 'I', 'F', 'F'});
      buf.putInt(0x7FFFFFFF); // Unknown file size — streaming
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
      buf.putInt(0x7FFFFFFF); // Unknown data size — streaming
      return buf.array();
    }

    // DLNA/UPnP raw audio/L16 mandates big-endian samples; Android's PCM_16BIT output is little-endian
    @NonNull
    private byte[] toBigEndian16(@NonNull byte[] littleEndianPcm) {
      final byte[] result = new byte[littleEndianPcm.length];
      for (int i = 0; i + 1 < littleEndianPcm.length; i += 2) {
        result[i] = littleEndianPcm[i + 1];
        result[i + 1] = littleEndianPcm[i];
      }
      return result;
    }
  }

  // Serves the audio stream in passthrough mode
  private class PassthroughStreamHandler extends BaseStreamHandler {
    private static final int PIPE_BUFFER_SIZE = 8192; // Matches default Java I/O buffer size

    @Override
    protected boolean accept(@NonNull String path) {
      return !path.endsWith(STREAM_SUFFIX_PCM);
    }

    @Override
    protected void handleStream(
      @NonNull HttpServer.Response response,
      @NonNull OutputStream responseStream,
      boolean isHead,
      @NonNull StreamContext streamContext) throws IOException {
      // HEAD
      final Radio.ConnectionSet connectionSet = streamContext.getRadio().getConnectionSet(SessionDevice.STREAMING_USER_AGENT);
      if (connectionSet == null) {
        Log.d(LOG_TAG, "PassthroughStreamHandler: upstream is not defined");
        streamContext.onDisconnected();
        return;
      }
      sendDlnaResponse(response, responseStream, UpnpSessionDevice.normalize(connectionSet.getContent()), streamContext);
      if (isHead) {
        return;
      }
      // New upstream connection per GET thread — a shared connection causes concurrent stream corruption
      final okhttp3.Response upstreamResponse;
      try {
        upstreamResponse = new RadioURL(connectionSet.getUrl()).getActualOkHttpResponse(
          SessionDevice.STREAMING_USER_AGENT,
          java.util.Collections.singletonMap("Icy-Metadata", "1"));
      } catch (IOException ioException) {
        Log.d(LOG_TAG, "PassthroughStreamHandler: unable to connect", ioException);
        streamContext.onDisconnected();
        throw ioException;
      }
      // We signal actual connection and start stream
      streamContext.onConnected();
      final String icyMetaIntValue = upstreamResponse.header("Icy-Metaint");
      final IcyStreamParser parser = (icyMetaIntValue == null) ? null :
        new IcyStreamParser(Integer.parseInt(icyMetaIntValue), streamContext::onNewInformation);
      final byte[] buf = new byte[PIPE_BUFFER_SIZE];
      int n;
      Log.d(LOG_TAG, "PassthroughStreamHandler: start streaming - " + streamContext.getLockKey());
      try (final InputStream inputStream = upstreamResponse.body().byteStream()) {
        while (streamContext.hasLockKey() && ((n = inputStream.read(buf)) >= 0)) {
          streamContext.relaunchWatchdog();
          if (parser == null) {
            responseStream.write(buf, 0, n);
          } else {
            responseStream.write(parser.parse(buf, n));
          }
        }
      } catch (IOException ioException) {
        Log.d(LOG_TAG, "PassthroughStreamHandler: IOException - " + streamContext.getLockKey() + "; " + ioException.getMessage());
        throw ioException;
      } finally {
        upstreamResponse.close();
      }
    }
  }
}