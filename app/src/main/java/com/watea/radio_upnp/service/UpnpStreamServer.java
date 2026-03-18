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
  private static final int GET_TIMEOUT = 5000; // ms
  private static final int QUEUE_SIZE = 100; // ~2-3s buffer at 48000Hz stereo 16-bit (4608 bytes/chunk)
  private static final int REMOTE_LOGO_SIZE = 300;
  private static final String LOGO_PREFIX = "logo";
  private static final String LOGO_SUFFIX = ".jpg";
  private static final long FAKE_STREAM_LENGTH = 0x7FFFFFFFL; // ~2GB
  @NonNull
  private final Callback callback;
  private final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(QUEUE_SIZE);
  @NonNull
  private final AtomicReference<String> lockKey = new AtomicReference<>(""); // Current stream signature
  private int sampleRate = DEFAULT;
  private int channelCount = DEFAULT;
  private int bitsPerSample = DEFAULT;
  private final CapturingAudioSink.Callback pcmCallback = new CapturingAudioSink.Callback() {
    @Override
    public void onFormatChanged(int sampleRate, int channelCount, int bitsPerSample) {
      // Update WAV header format
      setAudioFormat(sampleRate, channelCount, bitsPerSample);
    }

    @Override
    public void onPcmData(@NonNull byte[] pcmData, @NonNull String lockKey) {
      // Current stream signature
      UpnpStreamServer.this.lockKey.set(lockKey);
      // PCM 16-bit little-endian byte array
      feed(pcmData);
    }
  };
  @Nullable
  private String logoUri = null;
  @Nullable
  private byte[] logoBytes = null;

  public UpnpStreamServer(@NonNull Callback callback) throws IOException {
    super(0);
    this.callback = callback;
  }

  @NonNull
  public CapturingAudioSink.Callback getPcmCallback() {
    return pcmCallback;
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
    this.sampleRate = sampleRate;
    this.channelCount = channelCount;
    this.bitsPerSample = bitsPerSample;
    lockKey.set(callback.getLockKey());
  }

  public void feed(@NonNull final byte[] pcmData) {
    final int remaining = queue.remainingCapacity();
    if (remaining < QUEUE_SIZE * 0.2F)
      Log.w(TAG, "Queue fill: " + (QUEUE_SIZE - remaining) + "/" + QUEUE_SIZE);
    if (!queue.offer(pcmData)) Log.e(TAG, "QUEUE FULL DROP (" + pcmData.length + " bytes)");
  }

  // NanoHTTPD: HTTP response to UPnP renderer
  @Override
  public Response serve(final IHTTPSession session) {
    Log.d(TAG, "serve: " + session.getMethod() + " from " + session.getRemoteIpAddress() + " UA=" + session.getHeaders().get("user-agent"));
    final String uri = session.getUri().substring(1);
    // -- Logo --
    if (uri.equals(logoUri) && (logoBytes != null)) {
      return newFixedLengthResponse(Response.Status.OK, "image/jpeg", new ByteArrayInputStream(logoBytes), logoBytes.length);
    }
    // -- Stream --
    final boolean isGet = Method.GET.equals(session.getMethod());
    queue.clear();
    final long deadline = System.currentTimeMillis() + GET_TIMEOUT;
    while (isInactive()) {
      if (System.currentTimeMillis() > deadline) {
        return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Format timeout");
      }
      try {
        //noinspection BusyWait
        Thread.sleep(50);
      } catch (InterruptedException interruptedException) {
        Thread.currentThread().interrupt();
        return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Interrupted");
      }
    }
    return getResponse(isGet);
  }

  // Returns the stream URL to pass to the renderer via SetAVTransportURI
  @NonNull
  public Uri getStreamUri(@NonNull final String localIp) {
    return Uri.parse(SCHEME + localIp + ":" + getListeningPort() + "/stream.wav");
  }

  public void stopStream() {
    queue.clear();
  }

  private boolean isInactive() {
    return !callback.getLockKey().equals(lockKey.get());
  }

  @NonNull
  private Response getResponse(boolean isGet) {
    final InputStream stream = isGet ? new WavInputStream(sampleRate, channelCount, bitsPerSample, lockKey.get()) : new ByteArrayInputStream(new byte[0]);
    final Response response = newFixedLengthResponse(Response.Status.OK, MIME, stream, FAKE_STREAM_LENGTH);
    response.addHeader("transferMode.dlna.org", "Streaming");
    response.addHeader("contentFeatures.dlna.org", "DLNA.ORG_PN=LPCM;DLNA.ORG_OP=00;DLNA.ORG_CI=1;DLNA.ORG_FLAGS=01700000000000000000000000000000");
    response.addHeader("Accept-Ranges", "none");
    return response;
  }

  public interface Callback {
    void onDisconnect(@NonNull String lockKey);

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
      if (isInactive()) {
        throw new IOException("Stream stopped");
      }
      try {
        if (!started) {
          started = true;
          Log.i(TAG, "Renderer started reading stream");
        }
        if (pending == null) {
          pending = queue.poll(2, TimeUnit.SECONDS);
          pendingOffset = 0;
        }
        if (pending == null) {
          Log.w(TAG, "PCM timeout (2s) – stream stalled");
          // Something went wrong
          callback.onDisconnect(lockKey);
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
        // Something went wrong
        callback.onDisconnect(lockKey);
        throw new IOException("Interrupted", interruptedException);
      }
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