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
import androidx.core.view.MenuItemCompat;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.mediarouter.app.MediaRouteActionProvider;
import androidx.mediarouter.media.MediaRouteSelector;

import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.SessionManager;
import com.google.android.gms.cast.framework.SessionManagerListener;
import com.watea.radio_upnp.model.Radio;
import com.watea.radio_upnp.model.SessionDevice;

public class CastManager extends OpenCastManager<CastSessionDevice> {
  private static final String LOG_TAG = CastManager.class.getSimpleName();
  @NonNull
  private Callback callback = new Callback() {
  };
  @Nullable
  private CastSession castSession = null;
  private final SessionManagerListener<CastSession> sessionManagerListener = new SessionManagerListener<>() {
    private void release() {
      castSession = null;
      callback.onCastStop();
    }

    @Override
    public void onSessionStarting(@NonNull CastSession castSession) {
      CastManager.this.castSession = castSession;
      callback.onCastStarting();
    }

    @Override
    public void onSessionStarted(@NonNull CastSession session, @NonNull String sessionId) {
      callback.onCastStarted();
    }

    @Override
    public void onSessionStartFailed(@NonNull CastSession castSession, int i) {
      release();
    }

    @Override
    public void onSessionEnding(@NonNull CastSession castSession) {
      CastManager.this.castSession = null;
    }

    @Override
    public void onSessionEnded(@NonNull CastSession session, int error) {
      callback.onCastStop();
    }

    @Override
    public void onSessionResuming(@NonNull CastSession castSession, @NonNull String s) {
    }

    @Override
    public void onSessionResumed(@NonNull CastSession castSession, boolean b) {
      CastManager.this.castSession = castSession;
      callback.onCastStarted();
    }

    @Override
    public void onSessionResumeFailed(@NonNull CastSession castSession, int i) {
      release();
    }

    @Override
    public void onSessionSuspended(@NonNull CastSession castSession, int i) {
      release();
    }
  };

  private CastManager() {
  }

  @NonNull
  public static CastManager getInstance() {
    if (instance == null) {
      instance = new CastManager();
    }
    return (CastManager) instance;
  }

  public static void mediaRouteActionProviderSetRouteSelector(@NonNull Context context, @NonNull MenuItem castItem) {
    final MediaRouteActionProvider mediaRouteActionProvider = (MediaRouteActionProvider) MenuItemCompat.getActionProvider(castItem);
    if (mediaRouteActionProvider == null) {
      Log.e(LOG_TAG, "setRouteSelector: mediaRouteActionProvider not found");
    } else {
      final CastContext castContext = CastContext.getSharedInstance(context);
      final MediaRouteSelector mediaRouteSelector = castContext.getMergedSelector();
      if (mediaRouteSelector == null) {
        Log.e(LOG_TAG, "setRouteSelector: mediaRouteSelector not found");
      } else {
        mediaRouteActionProvider.setRouteSelector(mediaRouteSelector);
      }
    }
  }

  @Override
  public void setContext(@NonNull Context context, @NonNull OpenCastManager.Callback callback) {
    this.callback = callback;
    final SessionManager sessionManager = CastContext.getSharedInstance(context).getSessionManager();
    sessionManager.addSessionManagerListener(sessionManagerListener, CastSession.class);
    // Session is already running?
    final CastSession currentCastSession = sessionManager.getCurrentCastSession();
    if ((currentCastSession != null) && currentCastSession.isConnected()) {
      castSession = currentCastSession;
      callback.onCastStarted();
    }
  }

  @Override
  public void resetContext(@NonNull Context context) {
    CastContext.getSharedInstance(context).getSessionManager().removeSessionManagerListener(
      sessionManagerListener,
      CastSession.class);
    callback = new Callback() {
    };
    castSession = null;
  }

  @Override
  public boolean hasCastSession() {
    return (castSession != null) && castSession.isConnected();
  }

  @Override
  @NonNull
  public CastSessionDevice getCastSessionDevice(
    @NonNull Context context,
    @NonNull ExoPlayer exoPlayer,
    @NonNull SessionDevice.Listener listener,
    @NonNull String lockKey,
    @NonNull Radio radio,
    @NonNull Uri radioUri,
    @Nullable Uri logoUri) {
    assert castSession != null;
    return new CastSessionDevice(context, exoPlayer, listener, lockKey, radio, radioUri, logoUri, castSession);
  }
}