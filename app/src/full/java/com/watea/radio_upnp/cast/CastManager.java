package com.watea.radio_upnp.cast;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.MenuItemCompat;
import androidx.mediarouter.app.MediaRouteActionProvider;
import androidx.mediarouter.media.MediaRouteSelector;

import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.SessionManager;
import com.google.android.gms.cast.framework.SessionManagerListener;
import com.watea.radio_upnp.model.Radio;

import java.util.function.Consumer;

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
    @NonNull Consumer<Integer> listener,
    @NonNull String lockKey,
    @NonNull Radio radio,
    @NonNull Uri radioUri,
    @Nullable Uri logoUri) {
    assert castSession != null;
    return new CastSessionDevice(context, listener, lockKey, radio, radioUri, logoUri, castSession);
  }
}