package com.watea.radio_upnp.service;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.AuxEffectInfo;
import androidx.media3.common.Format;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.audio.AudioSink;

import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@OptIn(markerClass = UnstableApi.class)
public class CapturingAudioSink implements AudioSink {
  private static final String LOG_TAG = CapturingAudioSink.class.getSimpleName();
  private static final int PACER_TIMEOUT = 2; // s
  private static final int DEFAULT = -1;
  @NonNull
  private final AudioSink delegate;
  private final AtomicReference<String> lockKey = new AtomicReference<>("");
  private final LinkedBlockingQueue<byte[]> pcmBuffer = new LinkedBlockingQueue<>();
  @Nullable
  private Callback callback = null;
  @Nullable
  private Pacer pacer = null;
  private volatile long byteRate = DEFAULT;
  private volatile long lastPresentationTimeUs = 0; // Presentation time microseconds

  public CapturingAudioSink(@NonNull AudioSink delegate, @NonNull String lockKey) {
    this.delegate = delegate;
    this.lockKey.set(lockKey);
  }

  public void setCallback(@NonNull Callback callback) {
    this.callback = callback;
    pacer = new Pacer();
  }

  // Called before handleBuffer
  @Override
  public void configure(@NonNull Format inputFormat, int specifiedBufferSize, @Nullable int[] outputChannels)
    throws ConfigurationException {
    final int sampleRate = inputFormat.sampleRate;
    final int channelCount = inputFormat.channelCount;
    final int bytesPerSample = Util.getPcmFrameSize(inputFormat.pcmEncoding, 1);
    byteRate = (long) sampleRate * channelCount * bytesPerSample;
    if (callback != null) {
      callback.onFormatChanged(sampleRate, channelCount, bytesPerSample * 8);
    }
    delegate.configure(inputFormat, specifiedBufferSize, outputChannels);
  }

  // presentationTimeUs: microseconds, It is the timestamp in microseconds at which this audio frame must be presented (played) to the user, within the media timeline
  @Override
  public boolean handleBuffer(@NonNull ByteBuffer buffer, long presentationTimeUs, int encodedAccessUnitCount)
    throws InitializationException, WriteException {
    if (callback == null) {
      return delegate.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount);
    } else {
      lastPresentationTimeUs = presentationTimeUs;
      if (buffer.hasRemaining()) {
        final byte[] pcmData = new byte[buffer.remaining()];
        buffer.get(pcmData);
        pcmBuffer.add(pcmData);
      }
      return true;
    }
  }

  @Override
  public void playToEndOfStream() throws WriteException {
    delegate.playToEndOfStream();
  }

  @Override
  public void play() {
    delegate.play();
  }

  @Override
  public void pause() {
    delegate.pause();
  }

  @Override
  public void handleDiscontinuity() {
    delegate.handleDiscontinuity();
  }

  @Override
  public void flush() {
    pcmBuffer.clear();
    delegate.flush();
  }

  @Override
  public void reset() {
    stopPacer();
    pcmBuffer.clear();
    delegate.reset();
  }

  @Override
  public void release() {
    stopPacer();
    pcmBuffer.clear();
    delegate.release();
  }

  @Override
  public boolean isEnded() {
    return delegate.isEnded();
  }

  @Override
  public boolean hasPendingData() {
    return delegate.hasPendingData();
  }

  @NonNull
  @Override
  public PlaybackParameters getPlaybackParameters() {
    return delegate.getPlaybackParameters();
  }

  @Override
  public void setPlaybackParameters(@NonNull PlaybackParameters playbackParameters) {
    delegate.setPlaybackParameters(playbackParameters);
  }

  @Override
  public boolean getSkipSilenceEnabled() {
    return delegate.getSkipSilenceEnabled();
  }

  @Override
  public void setSkipSilenceEnabled(boolean skipSilenceEnabled) {
    delegate.setSkipSilenceEnabled(skipSilenceEnabled);
  }

  @Nullable
  @Override
  public AudioAttributes getAudioAttributes() {
    return delegate.getAudioAttributes();
  }

  @Override
  public void setAudioAttributes(@NonNull AudioAttributes audioAttributes) {
    delegate.setAudioAttributes(audioAttributes);
  }

  @Override
  public void setAudioSessionId(int audioSessionId) {
    delegate.setAudioSessionId(audioSessionId);
  }

  @Override
  public void setAuxEffectInfo(@NonNull AuxEffectInfo auxEffectInfo) {
    delegate.setAuxEffectInfo(auxEffectInfo);
  }

  @Override
  public long getAudioTrackBufferSizeUs() {
    return delegate.getAudioTrackBufferSizeUs();
  }

  @Override
  public void enableTunnelingV21() {
    delegate.enableTunnelingV21();
  }

  @Override
  public void disableTunneling() {
    delegate.disableTunneling();
  }

  @Override
  public void setVolume(float volume) {
    delegate.setVolume(volume);
  }

  @Override
  public void setListener(@NonNull Listener listener) {
    delegate.setListener(listener);
  }

  @Override
  public boolean supportsFormat(@NonNull Format format) {
    return delegate.supportsFormat(format);
  }

  @Override
  public int getFormatSupport(@NonNull Format format) {
    return delegate.getFormatSupport(format);
  }

  @Override
  public long getCurrentPositionUs(boolean sourceEnded) {
    return (callback == null) ? delegate.getCurrentPositionUs(sourceEnded) : lastPresentationTimeUs;
  }

  private void stopPacer() {
    if (pacer != null) {
      pacer.interrupt();
      pacer = null;
    }
  }

  public interface Callback {
    void onFormatChanged(int sampleRate, int channelCount, int bitsPerSample);

    void onPcmData(@NonNull byte[] data, @NonNull String lockKey);
  }

  // callback != null.
  // PCM: Pulse Code Modulation.
  private class Pacer extends Thread {
    private long bytesConsumed = 0L;

    private Pacer() {
      setDaemon(true);
      setName("PcmPacer");
      start();
    }

    @Override
    public void run() {
      long startTimeUs = -1;
      while (!Thread.currentThread().isInterrupted()) {
        try {
          final byte[] pcmData = pcmBuffer.poll(PACER_TIMEOUT, TimeUnit.SECONDS);
          if (pcmData == null) {
            Log.e(LOG_TAG, "pcmBuffer EMPTY — ExoPlayer stopped feeding");
            continue;
          }
          if (startTimeUs < 0) {
            startTimeUs = getTimestamp();
          }
          if (byteRate != DEFAULT) {
            final long expectedUs = (bytesConsumed * 1_000_000L) / byteRate;
            final long elapsedUs = getTimestamp() - startTimeUs;
            final long sleepUs = expectedUs - elapsedUs;
            if (sleepUs >= 1000) {
              //noinspection BusyWait
              Thread.sleep(sleepUs / 1000);
            }
          }
          bytesConsumed += pcmData.length;
          assert callback != null;
          callback.onPcmData(pcmData, lockKey.get());
        } catch (InterruptedException interruptedException) {
          Thread.currentThread().interrupt();
        }
      }
    }

    private long getTimestamp() {
      return System.nanoTime() / 1000;
    }
  }
}