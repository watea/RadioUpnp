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

@OptIn(markerClass = UnstableApi.class)
public class CapturingAudioSink implements AudioSink {
  private static final String LOG_TAG = CapturingAudioSink.class.getSimpleName();
  private static final int PACER_TIMEOUT = 2; // s
  private static final long LONG_DEFAULT = -1L;
  private static final int PCM_BUFFER_SIZE = 100; // ~2.5s at 48000Hz stereo 16-bit (4608 bytes/chunk)
  private static final long ONE_SECOND_US = 1_000_000L;
  private static final long PACER_SLEEP_MIN_US = 1_000L;
  private static final long BURST_DURATION_US = 2_000_000L;
  @NonNull
  private final AudioSink delegate;
  private final LinkedBlockingQueue<byte[]> pcmBuffer = new LinkedBlockingQueue<>(PCM_BUFFER_SIZE);
  @Nullable
  private Callback callback = null;
  @Nullable
  private Pacer pacer = null;
  private volatile long byteRate = LONG_DEFAULT;
  private volatile long lastPresentationTimeUs = 0; // Presentation time microseconds

  public CapturingAudioSink(@NonNull AudioSink delegate) {
    this.delegate = delegate;
  }

  public void setCallback(@NonNull Callback callback) {
    this.callback = callback;
    pacer = new Pacer();
  }

  // Called before handleBuffer
  @Override
  public void configure(@NonNull Format inputFormat, int specifiedBufferSize, @Nullable int[] outputChannels)
    throws ConfigurationException {
    if (callback != null) {
      final int sampleRate = inputFormat.sampleRate;
      final int channelCount = inputFormat.channelCount;
      final int bytesPerSample = Util.getPcmFrameSize(inputFormat.pcmEncoding, 1);
      Log.d(LOG_TAG, "configure: sampleRate=" + sampleRate + " channelCount=" + channelCount);
      byteRate = (long) sampleRate * channelCount * bytesPerSample;
      callback.onFormatChanged(sampleRate, channelCount, bytesPerSample * 8);
    }
    delegate.configure(inputFormat, specifiedBufferSize, outputChannels);
  }

  // presentationTimeUs: microseconds, it is the timestamp in microseconds at which this audio frame must be presented (played) to the user, within the media timeline
  @Override
  public boolean handleBuffer(@NonNull ByteBuffer buffer, long presentationTimeUs, int encodedAccessUnitCount)
    throws InitializationException, WriteException {
    if (callback == null) {
      return delegate.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount);
    } else {
      lastPresentationTimeUs = presentationTimeUs;
      if (buffer.hasRemaining()) {
        if (pcmBuffer.remainingCapacity() == 0) {
          return false; // ExoPlayer will retry later
        }
        final byte[] pcmData = new byte[buffer.remaining()];
        buffer.get(pcmData);
        pcmBuffer.offer(pcmData);
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
    return (callback == null) && delegate.isEnded();
  }

  @Override
  public boolean hasPendingData() {
    return (callback == null) ? delegate.hasPendingData() : !pcmBuffer.isEmpty();
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

  public void flushAndReset() {
    if (pacer != null) {
      pacer.flushAndReset();
    }
  }

  private void stopPacer() {
    if (pacer != null) {
      pacer.interrupt();
      pacer = null;
    }
  }

  public interface Callback {
    void onFormatChanged(int sampleRate, int channelCount, int bitsPerSample);

    void onPcmData(@NonNull byte[] data);
  }

  // callback != null.
  // PCM: Pulse Code Modulation.
  // Burst duration: send initial PCM data unthrottled to fill renderer's
  // internal buffer before switching to real-time pacing.
  private class Pacer extends Thread {
    private long bytesConsumed = 0L;
    private volatile boolean resetRequested = false;

    private Pacer() {
      setDaemon(true);
      setName("PcmPacer");
      start();
    }

    @Override
    public void run() {
      long startTimeUs = LONG_DEFAULT;
      long burstEndBytes = LONG_DEFAULT; // Threshold below which no pacing is applied

      while (!Thread.currentThread().isInterrupted()) {
        try {
          if (resetRequested) {
            resetRequested = false;
            startTimeUs = LONG_DEFAULT;
            burstEndBytes = LONG_DEFAULT;
            bytesConsumed = 0L;
            pcmBuffer.clear();
            Log.d(LOG_TAG, "Pacer timing reset for new renderer connection");
            continue;
          }
          final byte[] pcmData = pcmBuffer.poll(PACER_TIMEOUT, TimeUnit.SECONDS);
          if (pcmData == null) {
            Log.e(LOG_TAG, "pcmBuffer EMPTY — ExoPlayer stopped feeding");
            continue;
          }
          // Compute burst threshold once byteRate is known
          if ((burstEndBytes < 0) && (byteRate > 0)) {
            burstEndBytes = (byteRate * BURST_DURATION_US) / ONE_SECOND_US;
            Log.d(LOG_TAG, "Pacer burst phase: " + burstEndBytes + " bytes (" + (BURST_DURATION_US / ONE_SECOND_US) + "s)");
          }
          // Real-time pacing only after burst phase
          if ((burstEndBytes >= 0) && (bytesConsumed > burstEndBytes) && (byteRate > 0)) {
            if (startTimeUs < 0) {
              // Anchor the clock retroactively to account for bytes already sent
              // during burst, so pacing continues seamlessly from here.
              startTimeUs = getTimestamp() - getExpectedUs();
              Log.d(LOG_TAG, "Pacer burst complete, switching to real-time pacing");
            }
            final long elapsedUs = getTimestamp() - startTimeUs;
            final long sleepUs = getExpectedUs() - elapsedUs;
            if (sleepUs >= PACER_SLEEP_MIN_US) {
              //noinspection BusyWait
              Thread.sleep(sleepUs / 1000);
            }
          }
          bytesConsumed += pcmData.length;
          assert callback != null;
          callback.onPcmData(pcmData);
        } catch (InterruptedException interruptedException) {
          Thread.currentThread().interrupt();
        }
      }
    }

    public void flushAndReset() {
      resetRequested = true;
    }

    private long getTimestamp() {
      return System.nanoTime() / 1000;
    }

    private long getExpectedUs() {
      return (bytesConsumed * ONE_SECOND_US) / byteRate;
    }
  }
}