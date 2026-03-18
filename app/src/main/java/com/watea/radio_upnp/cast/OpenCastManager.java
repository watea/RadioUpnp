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

package com.watea.radio_upnp.cast;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.exoplayer.ExoPlayer;

import com.watea.radio_upnp.model.Radio;
import com.watea.radio_upnp.model.SessionDevice;

// Singleton.
// CastManager that does nothing.
@SuppressWarnings("unused")
public class OpenCastManager<T extends SessionDevice> {
  private static final String LOG_TAG = OpenCastManager.class.getSimpleName();
  @Nullable
  protected static OpenCastManager<?> instance = null;

  protected OpenCastManager() {
  }

  public static void mediaRouteActionProviderSetRouteSelector(@NonNull Context context, @NonNull MenuItem castItem) {
    Log.e(LOG_TAG, "mediaRouteActionProviderSetRouteSelector: invalid call");
  }

  // Only called in one place / app, when context is available
  public void setContext(@NonNull Context context, @NonNull OpenCastManager.Callback callback) {
  }

  // Only called in one place / app
  public void resetContext(@NonNull Context context) {
  }

  public boolean hasCastSession() {
    return false;
  }

  @Nullable
  public T getCastSessionDevice(
    @NonNull Context context,
    @NonNull ExoPlayer exoPlayer,
    @NonNull SessionDevice.Listener listener,
    @NonNull String lockKey,
    @NonNull Radio radio,
    @Nullable Uri logoUri) {
    Log.e(LOG_TAG, "getCastSessionDevice: invalid call");
    return null;
  }

  public interface Callback {
    default void onCastStarting() {
      Log.e(LOG_TAG, "onCastStarting: invalid call");
    }

    default void onCastStarted() {
      Log.e(LOG_TAG, "onCastStarted: invalid call");
    }

    default void onCastStop() {
      Log.e(LOG_TAG, "onCastStop: invalid call");
    }
  }
}