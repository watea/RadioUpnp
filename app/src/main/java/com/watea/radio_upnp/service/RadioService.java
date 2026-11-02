/*
 * Copyright (c) 2018-2026. Stephane Treuchot
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

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ServiceInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.car.app.connection.CarConnection;
import androidx.core.app.ServiceCompat;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaLibraryService;
import androidx.media3.session.MediaSession;

import com.watea.radio_upnp.R;
import com.watea.radio_upnp.cast.CastManager;
import com.watea.radio_upnp.model.Radio;
import com.watea.radio_upnp.model.Radios;
import com.watea.radio_upnp.service.SessionDevice.State;
import com.watea.radio_upnp.upnp.Device;

import java.io.IOException;
import java.util.Collection;

public class RadioService
  extends MediaLibraryService
  implements SessionDevice.Listener, CastManager.Callback, RadioPlayer.Commands {
  public static final boolean KEY_PCM_MODE_DEFAULT = true;
  public static final String ACTION_SLEEP_SET = "ACTION_SLEEP_SET";
  public static final String ACTION_SLEEP_CANCEL = "ACTION_SLEEP_CANCEL";
  private static final String LOG_TAG = RadioService.class.getSimpleName();
  private static final Handler HANDLER = new Handler(Looper.getMainLooper());
  private boolean isAndroidAutoConnected = false;
  @Nullable
  private SessionDevice sessionDevice = null;
  private boolean isLastRadioToLaunch = false;
  @Nullable
  private AndroidUpnpService.UpnpService upnpService = null;
  private final ServiceConnection upnpConnection = new ServiceConnection() {
    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
      upnpService = (AndroidUpnpService.UpnpService) service;
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
      upnpService = null;
    }
  };
  private MediaLibrarySession mediaLibrarySession;
  private final SleepController.Listener sleepListener = new SleepController.Listener() {
    @Override
    public void onSleepOn(boolean isSleepOn) {
      final Bundle extras = new Bundle(mediaLibrarySession.getSessionExtras());
      extras.putBoolean(getString(R.string.key_sleep_set), isSleepOn);
      mediaLibrarySession.setSessionExtras(extras);
    }

    @Override
    public void onSleepEnd() {
      onPause();
    }
  };
  private RadioPlayer radioPlayer;
  private NotificationChannels notificationChannels;
  private SleepController sleepController;
  private MediaLibrarySessionCallback mediaLibrarySessionCallback;
  @Nullable
  private StreamServer streamServer = null;
  @Nullable
  private LiveData<Integer> carConnectionLiveData = null;
  private CastManager castManager;
  private final Observer<Integer> carConnectionObserver = type -> {
    final boolean connected = (type != null) && (type != CarConnection.CONNECTION_TYPE_NOT_CONNECTED);
    final boolean wasConnected = isAndroidAutoConnected;
    isAndroidAutoConnected = connected;
    Log.d(LOG_TAG, "Android Auto: " + (isAndroidAutoConnected ? "CONNECTED" : "DISCONNECTED"));
    if (sessionDevice != null) {
      if (wasConnected && !connected) {
        sessionDevice.onAndroidAutoDisconnected();
      } else if (!wasConnected && connected && sessionDevice.isRemote() && isPlayingOrBuffering()) {
        // Remote session launched before connection was known (or ongoing at home): move it to the car
        play(sessionDevice.getRadio());
      }
    }
  };
  private final Radios.Listener radiosListener = new Radios.Listener() {
    @Override
    public void onPreferredChange() {
      notifyChildrenChanged();
    }

    @Override
    public void onAdd(@NonNull Radio radio) {
      notifyChildrenChanged();
    }

    @Override
    public void onAddAll(@NonNull Collection<? extends Radio> c) {
      notifyChildrenChanged();
    }

    @Override
    public void onChange(@NonNull Radio radio) {
      notifyChildrenChanged();
    }

    @Override
    public void onRemove(int index) {
      notifyChildrenChanged();
    }

    @Override
    public void onMove(int from, int to) {
      notifyChildrenChanged();
    }

    @Override
    public void onInitEnd() {
      Log.d(LOG_TAG, "onInitEnd");
      HANDLER.post(() -> {
        mediaLibrarySessionCallback.onRadiosInit();
        notifyChildrenChanged();
        if (isLastRadioToLaunch) {
          launchLastRadio();
          isLastRadioToLaunch = false;
        }
      });
    }

    private void notifyChildrenChanged() {
      mediaLibrarySessionCallback.notifyChildrenChanged(mediaLibrarySession);
    }
  };

  @OptIn(markerClass = UnstableApi.class)
  @Override
  public void onCreate() {
    super.onCreate();
    Log.d(LOG_TAG, "onCreate");
    // Off the main thread: reads system CA certificates from disk
    new Thread(SessionDevice::warmUpHttpDataSourceFactory).start();
    // Create RadioPlayer and MediaLibrarySession
    radioPlayer = new RadioPlayer(this, getString(R.string.remote));
    notificationChannels = new NotificationChannels(this, LOG_TAG, radioPlayer);
    sleepController = new SleepController(notificationChannels, sleepListener);
    mediaLibrarySessionCallback = new MediaLibrarySessionCallback(this, sleepController);
    mediaLibrarySession = new MediaLibraryService.MediaLibrarySession.Builder(this, radioPlayer, mediaLibrarySessionCallback)
      .setSessionActivity(notificationChannels.getContentIntent())
      .build();
    setMediaNotificationProvider(notificationChannels.getMediaNotificationProvider());
    // Bind to UPnP service
    if (!bindService(new Intent(this, AndroidUpnpService.class), upnpConnection, BIND_AUTO_CREATE)) {
      Log.e(LOG_TAG, "Internal failure; AndroidUpnpService not bound");
    }
    // Android Auto detection
    carConnectionLiveData = new CarConnection(this).getType();
    carConnectionLiveData.observeForever(carConnectionObserver);
    // Cast
    castManager = CastManager.getInstance();
    castManager.setContext(this, this);
    // Create radios if needed
    Radios.setInstance(this, null);
    Radios.getInstance().addListener(radiosListener);
    // Already initialized (e.g. by activity): no onInitEnd to come
    if (Radios.isInit()) {
      mediaLibrarySessionCallback.onRadiosInit();
    }
    // Launch HTTP server
    try {
      streamServer = new StreamServer(this);
      streamServer.start();
    } catch (IOException iOException) {
      Log.e(LOG_TAG, "HTTP server creation failed", iOException);
      streamServer = null;
    }
  }

  @Override
  public void onDestroy() {
    Log.d(LOG_TAG, "onDestroy");
    // Stop, release and null sessionDevice before super.onDestroy() so onUpdateNotification sees null and removes the notification
    if (sessionDevice != null) {
      sessionDevice.stop();
      sessionDevice.release();
      sessionDevice = null;
    }
    super.onDestroy();
    // Radios
    Radios.getInstance().removeListener(radiosListener);
    // Release HTTP server
    if (streamServer != null) {
      try {
        streamServer.stop();
      } catch (IOException ioException) {
        Log.d(LOG_TAG, "onDestroy: unable to stop HTTP server", ioException);
      }
    }
    // Release UPnP service
    unbindService(upnpConnection);
    // Android Auto detection
    if (carConnectionLiveData != null) {
      carConnectionLiveData.removeObserver(carConnectionObserver);
    }
    // Release Cast
    castManager.resetContext(this);
    // Finally session
    mediaLibrarySession.release();
  }

  @Override
  @Nullable
  public MediaLibrarySession onGetSession(@NonNull MediaSession.ControllerInfo controllerInfo) {
    return mediaLibrarySession;
  }

  @Override
  public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
    if ((intent != null) && (intent.getAction() != null)) {
      switch (intent.getAction()) {
        case NotificationChannels.ACTION_MEDIA_PLAY:
          onPlay();
          break;
        case NotificationChannels.ACTION_MEDIA_PAUSE:
          onPause();
          break;
        case NotificationChannels.ACTION_MEDIA_STOP:
          onStop();
          break;
        case NotificationChannels.ACTION_MEDIA_SKIP_NEXT:
          onSkipToNext();
          break;
        case NotificationChannels.ACTION_MEDIA_SKIP_PREVIOUS:
          onSkipToPrevious();
          break;
        case ACTION_SLEEP_CANCEL:
          sleepController.release();
          break;
      }
    }
    return super.onStartCommand(intent, flags, startId);
  }

  // InlinedApi: FOREGROUND_SERVICE_TYPE_* are compile-time constants, ignored by ServiceCompat before API 29
  @SuppressLint("InlinedApi")
  @Override
  public void onUpdateNotification(@NonNull MediaSession session, boolean startInForegroundRequired) {
    if (startInForegroundRequired) {
      // startForeground must be called when required to avoid RemoteServiceException
      try {
        ServiceCompat.startForeground(
          this,
          NotificationChannels.PLAYER_NOTIFICATION_ID,
          notificationChannels.getPlayerNotification(session),
          (sessionDevice != null) && sessionDevice.isRemote()
            ? ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            : ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
      } catch (IllegalStateException illegalStateException) {
        // Denied from the background when no foreground start is pending (e.g. Media3 user-engaged
        // timeout after an error); ForegroundServiceStartNotAllowedException (API 31+) extends IllegalStateException
        Log.e(LOG_TAG, "onUpdateNotification: unable to start foreground", illegalStateException);
      }
      if (sessionDevice == null) {
        stopForeground(STOP_FOREGROUND_REMOVE);
      }
    } else if (sessionDevice == null) {
      stopForeground(STOP_FOREGROUND_REMOVE);
    } else {
      notificationChannels.notifyPlayer(session);
    }
  }

  @Override
  public void onTaskRemoved(@Nullable Intent rootIntent) {
    super.onTaskRemoved(rootIntent);
    stopSelf();
  }

  @Override
  public void onCastStarting() {
    Log.d(LOG_TAG, "onCastStarting");
    // UPnP no longer possible
    if (upnpService != null) {
      upnpService.setSelectedDeviceIdentity(null);
    }
  }

  @Override
  public void onCastStarted() {
    final State state = getPlaybackState();
    Log.d(LOG_TAG, "onCastStarted with state: " + state);
    if ((sessionDevice != null) && ((state == State.BUFFERING) || (state == State.PAUSED) || (state == State.PLAYING))) {
      play(sessionDevice.getRadio());
    }
  }

  @Override
  public void onCastStop() {
    Log.d(LOG_TAG, "onCastStop");
    onStop();
  }

  @Override
  public void onNewInformation(@NonNull String information, @NonNull String lockKey) {
    runIfLocked(lockKey, () -> {
      Log.d(LOG_TAG, "onNewInformation: " + information);
      assert sessionDevice != null;
      radioPlayer.buildSessionMetadata(sessionDevice.getRadio(), information);
    });
  }

  @Override
  public void onNewBitrate(int bitrate, @NonNull String mimeType, @NonNull String lockKey) {
    runIfLocked(lockKey, () -> {
      Log.d(LOG_TAG, "onNewBitrate: " + bitrate);
      final String bitrateDisplay = (bitrate > 0) ? Integer.toString(bitrate) : "--";
      final Bundle extras = new Bundle(mediaLibrarySession.getSessionExtras());
      extras.putString(getString(R.string.key_bitrate), bitrateDisplay);
      extras.putString(getString(R.string.key_mime_type), mimeType);
      mediaLibrarySession.setSessionExtras(extras);
    });
  }

  @Override
  public void onVolumeChanged(int volume, @NonNull String lockKey) {
    runIfLocked(lockKey, () -> {
      Log.d(LOG_TAG, "onVolumeChanged: " + volume);
      radioPlayer.setRemoteVolume(volume);
    });
  }

  @Override
  public void onState(@NonNull State state, @NonNull String lockKey) {
    runIfLocked(lockKey, () -> {
      Log.d(LOG_TAG, "onState: " + state.name());
      if ((sessionDevice == null) || (getPlaybackState() == state)) {
        return;
      }
      radioPlayer.setState(state);
      switch (state) {
        case PAUSED:
          releaseResources();
        case PLAYING:
        case BUFFERING:
          break;
        case ERROR:
          if (sessionDevice.consumeRewind()) {
            play(sessionDevice.getRadio());
            break;
          }
        default:
          releaseResources();
          sessionDevice.release();
          sessionDevice = null;
          stopForeground(STOP_FOREGROUND_REMOVE);
      }
    });
  }

  @Override
  @NonNull
  public State getPlaybackState() {
    return radioPlayer.getSessionDeviceState();
  }

  @Override
  public void onPlay() {
    Log.d(LOG_TAG, "onPlay");
    if (sessionDevice == null) {
      isLastRadioToLaunch = !launchLastRadio();
    } else {
      sessionDevice.play();
    }
  }

  @Override
  public void onSetMediaItem(@NonNull String mediaId) {
    Log.d(LOG_TAG, "onSetMediaItem: " + mediaId);
    // Changing item keeps playing state: switch now, as the following play request is a no-op
    if (isPlayingOrBuffering()) {
      playFromMediaId(mediaId);
    } else {
      Radios.setLastPlayedRadioId(this, mediaId);
      // Selection drops a paused session: next play launches anew, honoring current environment (renderer, Android Auto)
      if (sessionDevice != null) {
        sessionDevice.release();
        sessionDevice = null;
      }
    }
  }

  @Override
  public void onPause() {
    Log.d(LOG_TAG, "onPause");
    if (sessionDevice == null) {
      Log.e(LOG_TAG, "onPause: sessionDevice is null");
      return;
    }
    sessionDevice.pause();
  }

  @Override
  public void onStop() {
    Log.d(LOG_TAG, "onStop");
    if (sessionDevice == null) {
      Log.e(LOG_TAG, "onStop: sessionDevice is null");
      return;
    }
    onState(State.STOPPED, sessionDevice.getLockKey());
    sessionDevice.stop();
  }

  @Override
  public void onSkipToNext() {
    Log.d(LOG_TAG, "onSkipToNext");
    skipTo(1);
  }

  @Override
  public void onSkipToPrevious() {
    Log.d(LOG_TAG, "onSkipToPrevious");
    skipTo(-1);
  }

  @Override
  public void onAdjustVolume(int direction) {
    if (sessionDevice == null) {
      Log.e(LOG_TAG, "onAdjustVolume: sessionDevice is null");
      return;
    }
    sessionDevice.adjustVolume(direction);
  }

  private boolean isPlayingOrBuffering() {
    final State state = getPlaybackState();
    return (state == State.PLAYING) || (state == State.BUFFERING);
  }

  private void runIfLocked(@NonNull final String lockKey, @NonNull final Runnable runnable) {
    HANDLER.post(() -> {
      if ((sessionDevice != null) && lockKey.equals(sessionDevice.getLockKey())) {
        runnable.run();
      }
    });
  }

  private void releaseResources() {
    if (streamServer != null) {
      streamServer.release();
    }
    sleepController.release();
  }

  private void play(@NonNull Radio radio) {
    Log.d(LOG_TAG, "play: " + radio.getName() + " => " + radio.getUri());
    Radios.setLastPlayedRadioId(this, radio.getId());
    final Radio lastRadio = (sessionDevice == null) ? null : sessionDevice.getRadio();
    if (sessionDevice != null) {
      sessionDevice.release();
    }
    sleepController.release();
    sessionDevice = createSessionDevice(radio);
    mediaLibrarySession.setSessionExtras(new Bundle());
    radioPlayer.init(radio, sessionDevice.isRemote(), (radio == lastRadio));
    sessionDevice.launch();
    try {
      startForegroundService(new Intent(this, RadioService.class));
    } catch (IllegalStateException illegalStateException) {
      // May be denied when triggered from the background (e.g. auto-retry after a stream drop);
      // ForegroundServiceStartNotAllowedException (API 31+) also extends IllegalStateException
      Log.e(LOG_TAG, "play: unable to (re)start foreground service", illegalStateException);
    }
  }

  private void playFromMediaId(@NonNull String mediaId) {
    Log.d(LOG_TAG, "playFromMediaId: " + mediaId);
    final Radio radio = Radios.getInstance().getRadioFromId(mediaId);
    if (radio == null) {
      Log.e(LOG_TAG, "playFromMediaId: radio not found");
    } else {
      play(radio);
    }
  }

  private void skipTo(int direction) {
    final Radio currentRadio = Radios.getInstance().getLastPlayedRadio(this);
    if (currentRadio == null) {
      Log.d(LOG_TAG, "skipTo: no current radio");
      return;
    }
    final Radio nextRadio = Radios.getInstance().getRadioFrom(currentRadio, direction);
    if (nextRadio == null) {
      // Shall not happen
      Log.e(LOG_TAG, "skipTo: next radio is null");
    } else {
      play(nextRadio);
    }
  }

  // true if work done
  private boolean launchLastRadio() {
    Log.d(LOG_TAG, "launchLastRadio");
    if (Radios.isInit()) {
      final Radio radio = mediaLibrarySessionCallback.getLastRadio();
      if (radio != null) {
        play(radio);
      }
      return true;
    }
    return false;
  }

  // UPnP or Cast not accepted if environment not OK: force local processing
  @NonNull
  private SessionDevice createSessionDevice(@NonNull Radio radio) {
    SessionDevice result = null;
    if (!isAndroidAutoConnected && (streamServer != null) && new NetworkProxy(this).isOnWifi()) {
      final Device upnpSelectedDevice = (upnpService == null) ? null : upnpService.getActiveSelectedDevice();
      if (castManager.hasCastSession()) {
        result = castManager.getCastSessionDevice(
          this,
          this,
          radio,
          this::play,
          streamServer);
      } else if (upnpSelectedDevice != null) {
        result = new UpnpSessionDevice(
          this,
          Radios.getAppPreferences(this).getBoolean(getString(R.string.key_pcm_mode), KEY_PCM_MODE_DEFAULT),
          this,
          radio,
          this::play,
          streamServer,
          upnpService.getRequestController(),
          upnpSelectedDevice);
      }
    }
    if (result == null) {
      result = new LocalSessionDevice(
        this,
        this,
        radio);
    }
    Log.d(LOG_TAG, "createSessionDevice: " + result.getClass().getSimpleName() + " - " + result.getLockKey());
    return result;
  }
}