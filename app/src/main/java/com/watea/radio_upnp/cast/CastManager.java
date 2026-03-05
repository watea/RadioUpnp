package com.watea.radio_upnp.cast;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.SessionManager;
import com.google.android.gms.cast.framework.SessionManagerListener;

// Singleton
public class CastManager {
  @Nullable
  private static CastManager instance = null;
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

  public static synchronized CastManager getInstance() {
    if (instance == null) {
      instance = new CastManager();
    }
    return instance;
  }

  // Only called in one place / app, when context is available
  public void setContext(@NonNull Context context, @NonNull Callback callback) {
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

  // Only called in one place / app
  public void resetContext(@NonNull Context context) {
    CastContext.getSharedInstance(context).getSessionManager().removeSessionManagerListener(
      sessionManagerListener,
      CastSession.class);
    callback = new Callback() {
    };
    castSession = null;
  }

  @Nullable
  public CastSession getCastSession() {
    return castSession;
  }

  public interface Callback {
    default void onCastStarting() {
    }

    default void onCastStarted() {
    }

    default void onCastStop() {
    }
  }
}