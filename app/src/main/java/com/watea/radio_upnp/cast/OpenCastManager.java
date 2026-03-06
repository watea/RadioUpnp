package com.watea.radio_upnp.cast;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.watea.radio_upnp.model.Radio;
import com.watea.radio_upnp.model.SessionDevice;

import java.util.function.Consumer;

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
    @NonNull Consumer<Integer> listener,
    @NonNull String lockKey,
    @NonNull Radio radio,
    @NonNull Uri radioUri,
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