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
import androidx.media3.common.Format;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.ForwardingAudioSink;

import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

@OptIn(markerClass = UnstableApi.class)
public class CapturingAudioSink extends ForwardingAudioSink {
  private static final String LOG_TAG = CapturingAudioSink.class.getSimpleName();
  private static final long LONG_DEFAULT = -1L;
  private static final int PCM_BUFFER_SIZE = 100; // ~2.5s at 48000Hz stereo 16-bit (4608 bytes/chunk)
  private final LinkedBlockingQueue<byte[]> pcmBuffer = new LinkedBlockingQueue<>(PCM_BUFFER_SIZE);
  @Nullable
  private Callback callback = null;
  @Nullable
  private Pacer pacer = null;
  private volatile long byteRate = LONG_DEFAULT;
  private volatile long lastPresentationTimeUs = 0; // Presentation time microseconds

  public CapturingAudioSink(@NonNull AudioSink delegate) {
    super(delegate);
  }

  public void setCallback(@NonNull Callback callback) {
    this.callback = callback;
    pacer = new Pacer();
  }

  // Called before handleBuffer
  @Override
  public void configure(@NonNull AudioSinkConfig audioSinkConfig) throws ConfigurationException {
    if (callback != null) {
      final Format inputFormat = audioSinkConfig.format;
      final int sampleRate = inputFormat.sampleRate;
      final int channelCount = inputFormat.channelCount;
      final int bytesPerSample = Util.getPcmFrameSize(inputFormat.pcmEncoding, 1);
      Log.d(LOG_TAG, "configure: sampleRate = " + sampleRate + " channelCount = " + channelCount);
      byteRate = (long) sampleRate * channelCount * bytesPerSample;
      callback.onFormatChanged(sampleRate, channelCount, bytesPerSample * 8);
    }
    super.configure(audioSinkConfig);
  }

  // presentationTimeUs: microseconds, it is the timestamp in microseconds at which this audio frame must be presented (played) to the user, within the media timeline
  @Override
  public boolean handleBuffer(@NonNull ByteBuffer buffer, long presentationTimeUs, int encodedAccessUnitCount)
    throws InitializationException, WriteException {
    if (callback == null) {
      return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount);
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
  public void flush() {
    pcmBuffer.clear();
    super.flush();
  }

  @Override
  public void reset() {
    stopPacer();
    pcmBuffer.clear();
    super.reset();
  }

  @Override
  public void release() {
    stopPacer();
    pcmBuffer.clear();
    super.release();
  }

  @Override
  public boolean isEnded() {
    return (callback == null) && super.isEnded();
  }

  @Override
  public boolean hasPendingData() {
    return (callback == null) ? super.hasPendingData() : !pcmBuffer.isEmpty();
  }

  @Override
  public long getCurrentPositionUs(boolean sourceEnded) {
    return (callback == null) ? super.getCurrentPositionUs(sourceEnded) : lastPresentationTimeUs;
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

  private class Pacer extends Thread {
    private static final int PACER_TIMEOUT = 2; // s
    private static final int PCM_BUFFER_LOW_THRESHOLD = 10; // ~0.25s
    private static final long ONE_SECOND_US = 1_000_000L;
    private static final long PACER_SLEEP_MIN_US = 1_000L;
    private long bytesConsumed = 0L;

    private Pacer() {
      setDaemon(true);
      setName("PcmPacer");
      start();
    }

    @Override
    public void run() {
      long startTimeUs = LONG_DEFAULT;
      while (!Thread.currentThread().isInterrupted()) {
        try {
          assert callback != null;
          final byte[] pcmData = pcmBuffer.poll(PACER_TIMEOUT, TimeUnit.SECONDS);
          if (pcmData == null) {
            Log.e(LOG_TAG, "pcmBuffer EMPTY — ExoPlayer stopped feeding");
            continue;
          }
          final int bufferSize = pcmBuffer.size();
          if (bufferSize < PCM_BUFFER_LOW_THRESHOLD) {
            Log.w(LOG_TAG, "pcmBuffer LOW: " + bufferSize + "/" + PCM_BUFFER_SIZE);
          }
          // Guard byteRate
          if (byteRate <= 0) {
            // Shall not happen
            Log.e(LOG_TAG, "Pacer: byteRate not yet known");
          } else {
            final long elapsedUs;
            if (startTimeUs < 0) {
              startTimeUs = getTimestamp(); // Anchor clock on first chunk
              elapsedUs = 0;
            } else {
              elapsedUs = getTimestamp() - startTimeUs;
            }
            final long expectedUs = (bytesConsumed * ONE_SECOND_US) / byteRate;
            final long sleepUs = expectedUs - elapsedUs;
            if (sleepUs >= PACER_SLEEP_MIN_US) {
              //noinspection BusyWait
              Thread.sleep(sleepUs / 1000);
            }
          }
          bytesConsumed += pcmData.length;
          callback.onPcmData(pcmData);
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