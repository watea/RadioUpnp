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

import com.watea.radio_upnp.model.Radio;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import fi.iki.elonen.NanoHTTPD;

@OptIn(markerClass = UnstableApi.class)
public class UpnpStreamServer extends NanoHTTPD {
  public static final String MIME = "audio/wav";
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
  private static final long FAKE_STREAM_LENGTH = 0x7FFFFFFFL; // ~2GB
  @NonNull
  private final Callback callback;
  private final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(QUEUE_SIZE);
  private final AtomicReference<String> lockKey = new AtomicReference<>(""); // Current stream signature
  private int sampleRate = DEFAULT;
  private int channelCount = DEFAULT;
  private int bitsPerSample = DEFAULT;
  @Nullable
  private String logoUri = null;
  @Nullable
  private byte[] logoBytes = null;

  public UpnpStreamServer(@NonNull Callback callback) throws IOException {
    super(0);
    this.callback = callback;
  }

  @Nullable
  private static String extractLockKey(@NonNull String uri) {
    if (uri.startsWith(STREAM_PREFIX) && uri.endsWith(STREAM_SUFFIX)) {
      return uri.substring(STREAM_PREFIX.length(), uri.length() - STREAM_SUFFIX.length());
    }
    return null;
  }

  @NonNull
  public CapturingAudioSink.Callback getPcmCallback() {
    return new CapturingAudioSink.Callback() {
      @Override
      public void onFormatChanged(int sampleRate, int channelCount, int bitsPerSample) {
        // Update WAV header format
        setAudioFormat(sampleRate, channelCount, bitsPerSample);
      }

      @Override
      public void onPcmData(@NonNull byte[] pcmData) {
        // PCM 16-bit little-endian byte array
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
    logoUri = LOGO_PREFIX + radio.getId() + LOGO_SUFFIX;
    return Uri.parse(SCHEME + localIp + ":" + getListeningPort() + "/" + logoUri);
  }

  // Called before starting the stream
  public void setAudioFormat(int sampleRate, int channelCount, int bitsPerSample) {
    Log.d(TAG, "setAudioFormat");
    this.sampleRate = sampleRate;
    this.channelCount = channelCount;
    this.bitsPerSample = bitsPerSample;
    lockKey.set(callback.getLockKey());
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

  // NanoHTTPD: HTTP response to UPnP renderer
  @Override
  public Response serve(final IHTTPSession session) {
    Log.d(TAG, "serve: " + session.getMethod() + " from " + session.getRemoteIpAddress() + " UA=" + session.getHeaders().get("user-agent"));
    final String uri = session.getUri().substring(1);
    // -- Logo --
    if (uri.equals(logoUri)) {
      Log.d(TAG, "serve => logo");
      return (logoBytes == null) ?
        newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "No logo available") :
        newFixedLengthResponse(Response.Status.OK, "image/jpeg", new ByteArrayInputStream(logoBytes), logoBytes.length);
    }
    // -- Stream --
    // Wait for audio format to be ready
    final long deadline = System.currentTimeMillis() + GET_TIMEOUT;
    while (!callback.getLockKey().equals(lockKey.get())) {
      if (System.currentTimeMillis() > deadline) {
        final Response response = newFixedLengthResponse(Response.Status.lookup(503), MIME_PLAINTEXT, "Not ready yet");
        response.addHeader("Retry-After", "1");
        Log.d(TAG, "serve => Not ready yet");
        return response;
      }
      try {
        //noinspection BusyWait
        Thread.sleep(50);
      } catch (InterruptedException interruptedException) {
        Thread.currentThread().interrupt();
        Log.d(TAG, "serve => Interrupted");
        return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Interrupted");
      }
    }
    final String incomingLockKey = extractLockKey(uri);
    if (incomingLockKey == null) {
      Log.d(TAG, "serve => Invalid request");
      return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid request");
    }
    if (!incomingLockKey.equals(callback.getLockKey())) {
      Log.d(TAG, "serve => Stale session");
      return newFixedLengthResponse(Response.Status.GONE, MIME_PLAINTEXT, "Stale session");
    }
    queue.clear();
    callback.onConnected(incomingLockKey);
    return getResponse(session.getMethod(), incomingLockKey);
  }

  // Returns the stream URL to pass to the renderer via SetAVTransportURI
  public Uri getStreamUri(@NonNull String localIp, @NonNull String lockKey) {
    return Uri.parse(SCHEME + localIp + ":" + getListeningPort() + "/" + STREAM_PREFIX + lockKey + STREAM_SUFFIX);
  }

  @NonNull
  private Response getResponse(@Nullable Method method, @NonNull String lockKey) {
    final boolean isGet = Method.GET.equals(method);
    final boolean isHead = Method.HEAD.equals(method);
    if (!isGet && !isHead) {
      return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid request");
    }
    final InputStream inputStream = isGet ? new WavInputStream(sampleRate, channelCount, bitsPerSample, lockKey) : new ByteArrayInputStream(new byte[0]);
    final Response response = newFixedLengthResponse(Response.Status.OK, MIME, inputStream, FAKE_STREAM_LENGTH);
    response.addHeader("transferMode.dlna.org", "Streaming");
    response.addHeader("contentFeatures.dlna.org", "DLNA.ORG_PN=LPCM;DLNA.ORG_OP=00;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000");
    Log.d(TAG, "serve => OK " + lockKey);
    return response;
  }

  public interface Callback {
    void onDisconnect(@NonNull String lockKey);

    void onConnected(@NonNull String lockKey);

    @NonNull
    String getLockKey();
  }

  // Prepend a WAV header to the raw PCM stream
  private class WavInputStream extends InputStream {
    @NonNull
    private final String lockKey;
    @Nullable
    private byte[] pending = null;
    private int pendingOffset = 0;
    private boolean started = false;

    public WavInputStream(int sampleRate, int channelCount, int bitsPerSample, @NonNull String lockKey) {
      this.lockKey = lockKey;
      pending = buildWavHeader(sampleRate, channelCount, bitsPerSample);
    }

    @Override
    public int read() throws IOException {
      final byte[] buf = new byte[1];
      return (read(buf, 0, 1) == -1) ? -1 : (buf[0] & 0xFF);
    }

    @Override
    public int read(final byte[] buf, final int off, final int len) throws IOException {
      if (!lockKey.equals(callback.getLockKey())) {
        throw new IOException("Stream stopped");
      }
      try {
        if (!started) {
          started = true;
          Log.i(TAG, "Renderer started reading stream");
        }
        if (pending == null) {
          pending = queue.poll(PACER_POLL_TIMEOUT, TimeUnit.SECONDS);
          pendingOffset = 0;
        }
        if (pending == null) {
          Log.w(TAG, "PCM timeout (" + PACER_POLL_TIMEOUT + "s) – stream stalled");
          throw new IOException("Stream stalled");
        }
        final int toCopy = Math.min(pending.length - pendingOffset, len);
        System.arraycopy(pending, pendingOffset, buf, off, toCopy);
        pendingOffset += toCopy;
        if (pendingOffset >= pending.length) {
          pending = null;
        }
        return toCopy;
      } catch (InterruptedException interruptedException) {
        Thread.currentThread().interrupt();
        throw new IOException("Interrupted", interruptedException);
      }
    }

    @Override
    public void close() throws IOException {
      super.close();
      callback.onDisconnect(lockKey);
    }

    // Builds a standard 44-byte WAV header.
    // Size fields are set to 0xFFFFFFFF to indicate an unbounded stream, which is the common practice for HTTP audio streaming.
    private byte[] buildWavHeader(final int sampleRate, final int channelCount, final int bitsPerSample) {
      final int byteRate = sampleRate * channelCount * (bitsPerSample / 8);
      final int blockAlign = channelCount * (bitsPerSample / 8);
      final ByteBuffer buf = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
      // RIFF chunk
      buf.put(new byte[]{'R', 'I', 'F', 'F'});
      buf.putInt(0xFFFFFFFF); // Unknown file size — streaming
      buf.put(new byte[]{'W', 'A', 'V', 'E'});
      // fmt chunk
      buf.put(new byte[]{'f', 'm', 't', ' '});
      buf.putInt(16); // fmt chunk size
      buf.putShort((short) 1); // PCM format
      buf.putShort((short) channelCount);
      buf.putInt(sampleRate);
      buf.putInt(byteRate);
      buf.putShort((short) blockAlign);
      buf.putShort((short) bitsPerSample);
      // data chunk
      buf.put(new byte[]{'d', 'a', 't', 'a'});
      buf.putInt(0xFFFFFFFF); // Unknown data size — streaming
      return buf.array();
    }
  }
}