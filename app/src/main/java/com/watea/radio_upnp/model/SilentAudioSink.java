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

package com.watea.radio_upnp.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.AuxEffectInfo;
import androidx.media3.common.Format;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.audio.AudioSink;

import java.nio.ByteBuffer;

@OptIn(markerClass = UnstableApi.class)
public class SilentAudioSink implements AudioSink {
  private long lastPresentationTimeUs = 0;

  @Override
  public void configure(@NonNull Format inputFormat, int specifiedBufferSize, @Nullable int[] outputChannels) {
  }

  @Override
  public boolean handleBuffer(@NonNull ByteBuffer buffer, long presentationTimeUs, int encodedAccessUnitCount) {
    // Track last presentation time so MetadataRenderer clock stays in sync
    if (presentationTimeUs > 0) {
      lastPresentationTimeUs = presentationTimeUs;
    }
    buffer.position(buffer.limit());
    return true;
  }

  @Override
  public void play() {
  }

  @Override
  public void pause() {
  }

  @Override
  public void flush() {
    lastPresentationTimeUs = 0;
  }

  @Override
  public void reset() {
    lastPresentationTimeUs = 0;
  }

  @Override
  public void handleDiscontinuity() {
  }

  @Override
  public void playToEndOfStream() {
  }

  @Override
  public void setVolume(float volume) {
  }

  @Override
  public void setAudioSessionId(int audioSessionId) {
  }

  @Override
  public void setAuxEffectInfo(@NonNull AuxEffectInfo auxEffectInfo) {
  }

  @Override
  public void enableTunnelingV21() {
  }

  @Override
  public void disableTunneling() {
  }

  @Override
  public void setListener(@NonNull Listener listener) {
  }

  @Override
  public boolean isEnded() {
    // Must return false to keep ExoPlayer's rendering loop alive for live streams
    return false;
  }

  @Override
  public boolean hasPendingData() {
    // Must return true to keep MediaCodecAudioRenderer.isReady() = true,
    // which keeps the StandaloneMediaClock running for ICY metadata delivery
    return true;
  }

  @Override
  public boolean supportsFormat(@NonNull Format format) {
    return true;
  }

  @Override
  public int getFormatSupport(@NonNull Format format) {
    return AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY;
  }

  @Override
  public long getCurrentPositionUs(boolean sourceEnded) {
    // Return last known presentation time so MetadataRenderer can fire ICY events
    return lastPresentationTimeUs;
  }

  @Override
  public long getAudioTrackBufferSizeUs() {
    return 0;
  }

  @Override
  public boolean getSkipSilenceEnabled() {
    return false;
  }

  @Override
  public void setSkipSilenceEnabled(boolean skipSilenceEnabled) {
  }

  @Nullable
  @Override
  public AudioAttributes getAudioAttributes() {
    return null;
  }

  @Override
  public void setAudioAttributes(@NonNull AudioAttributes audioAttributes) {
  }

  @NonNull
  @Override
  public PlaybackParameters getPlaybackParameters() {
    return PlaybackParameters.DEFAULT;
  }

  @Override
  public void setPlaybackParameters(@NonNull PlaybackParameters playbackParameters) {
  }
}