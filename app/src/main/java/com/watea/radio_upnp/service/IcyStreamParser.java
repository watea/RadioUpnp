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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class IcyStreamParser {
  private static final Pattern STREAM_TITLE_PATTERN = Pattern.compile("StreamTitle='([^;]*)';");
  private final int icyMetaInt;
  @NonNull
  private final Consumer<String> metaConsumer;
  private final ByteArrayOutputStream audioOut = new ByteArrayOutputStream();
  @NonNull
  private byte[] metaBuffer = new byte[0];
  @NonNull
  private State state = State.AUDIO;
  private int audioRemaining;
  private int metaLength;
  private int metaRead;

  public IcyStreamParser(int icyMetaInt, @NonNull Consumer<String> metaConsumer) {
    this.icyMetaInt = icyMetaInt;
    this.audioRemaining = icyMetaInt;
    this.metaConsumer = metaConsumer;
  }

  @Nullable
  private static String extractTitle(@NonNull String meta) {
    final Matcher matcher = STREAM_TITLE_PATTERN.matcher(meta);
    return matcher.find() ? matcher.group(1) : null;
  }

  @NonNull
  public byte[] parse(@NonNull byte[] chunk, int length) {
    audioOut.reset();
    int pos = 0;
    while (pos < length) {
      switch (state) {
        case AUDIO: {
          final int available = Math.min(audioRemaining, length - pos);
          audioOut.write(chunk, pos, available);
          pos += available;
          audioRemaining -= available;
          if (audioRemaining == 0) {
            state = State.META_LENGTH;
          }
          break;
        }
        case META_LENGTH: {
          metaLength = (chunk[pos++] & 0xFF) * 16;
          metaRead = 0;
          if (metaLength == 0) {
            audioRemaining = icyMetaInt;
            state = State.AUDIO;
          } else {
            metaBuffer = new byte[metaLength];
            state = State.META_DATA;
          }
          break;
        }
        case META_DATA: {
          final int available = Math.min(metaLength - metaRead, length - pos);
          System.arraycopy(chunk, pos, metaBuffer, metaRead, available);
          metaRead += available;
          pos += available;
          if (metaRead == metaLength) {
            final String title = extractTitle(new String(metaBuffer, StandardCharsets.UTF_8));
            if (title != null) {
              metaConsumer.accept(title);
            }
            audioRemaining = icyMetaInt;
            state = State.AUDIO;
          }
          break;
        }
      }
    }
    return audioOut.toByteArray();
  }

  private enum State {AUDIO, META_LENGTH, META_DATA}
}