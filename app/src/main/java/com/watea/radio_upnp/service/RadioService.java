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

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.car.app.connection.CarConnection;
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

public class RadioService extends MediaLibraryService {
  public static final boolean KEY_PCM_MODE_DEFAULT = true;
  public static final String ACTION_SLEEP_SET = "ACTION_SLEEP_SET";
  public static final String ACTION_SLEEP_CANCEL = "ACTION_SLEEP_CANCEL";
  public static final String ACTION_ALARM_PLAY = "ACTION_ALARM_PLAY";
  private static final String LOG_TAG = RadioService.class.getSimpleName();
  private static final Handler HANDLER = new Handler(Looper.getMainLooper());
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
  private final SleepController.Listener sleepControllerListener = new SleepController.Listener() {
    @Override
    public void onSleepOn(boolean isSleepOn) {
      final Bundle extras = new Bundle(mediaLibrarySession.getSessionExtras());
      extras.putBoolean(getString(R.string.key_sleep_set), isSleepOn);
      mediaLibrarySession.setSessionExtras(extras);
    }

    @Override
    public void onSleepEnd() {
      radioPlayerCommands.onPause();
    }
  };
  private final Observer<Integer> carConnectionObserver = new Observer<>() {
    @Override
    public void onChanged(Integer type) {
      final boolean connected = (type != null) && (type != CarConnection.CONNECTION_TYPE_NOT_CONNECTED);
      final boolean wasConnected = isAndroidAutoConnected;
      isAndroidAutoConnected = connected;
      Log.d(LOG_TAG, "Android Auto: " + (isAndroidAutoConnected ? "CONNECTED" : "DISCONNECTED"));
      if (sessionDevice != null) {
        if (wasConnected && !connected) {
          sessionDevice.onAndroidAutoDisconnected();
        } else if (!wasConnected && connected && sessionDevice.isRemote() && sessionDevice.isPlayingOrBuffering()) {
          // Remote session launched before connection was known (or ongoing at home): move it to the car
          play(sessionDevice.getRadio());
        }
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
  private final CastManager.Callback castManagerCallback = new CastManager.Callback() {
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
      Log.d(LOG_TAG, "onCastStarted");
      if ((sessionDevice != null) && sessionDevice.isRunning()) {
        play(sessionDevice.getRadio());
      }
    }

    @Override
    public void onCastStop() {
      Log.d(LOG_TAG, "onCastStop");
      radioPlayerCommands.onStop();
    }
  };
  private final SessionDevice.Listener sessionDeviceListener = new SessionDevice.Listener() {
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
        assert sessionDevice != null;
        radioPlayer.onSessionDeviceState(state);
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
            releaseSessionDevice();
        }
      });
    }
  };
  private final RadioPlayer.Commands radioPlayerCommands = new RadioPlayer.Commands() {
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
    public void onPlayFromMediaId(@NonNull String mediaId) {
      Log.d(LOG_TAG, "onPlayFromMediaId: " + mediaId);
      final Radio radio = Radios.getInstance().getRadioFromId(mediaId);
      if (radio == null) {
        Log.e(LOG_TAG, "onPlayFromMediaId: radio not found");
      } else {
        play(radio);
      }
    }

    @Override
    public void onSelectMediaItem(@NonNull String mediaId) {
      Log.d(LOG_TAG, "onSelectMediaItem: " + mediaId);
      Radios.setLastPlayedRadioId(RadioService.this, mediaId);
      releaseSessionDevice();
    }

    @Override
    public void onPause() {
      Log.d(LOG_TAG, "onPause");
      if (sessionDevice == null) {
        return;
      }
      sessionDevice.pause();
    }

    @Override
    public void onStop() {
      Log.d(LOG_TAG, "onStop");
      if (sessionDevice == null) {
        return;
      }
      sessionDeviceListener.onState(State.STOPPED, sessionDevice.getLockKey());
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
        return;
      }
      sessionDevice.adjustVolume(direction);
    }
  };
  private boolean isAndroidAutoConnected = false;
  @Nullable
  private SessionDevice sessionDevice = null;
  private boolean isLastRadioToLaunch = false;
  @Nullable
  private AndroidUpnpService.UpnpService upnpService = null;
  private MediaLibrarySession mediaLibrarySession;
  private RadioPlayer radioPlayer;
  private SleepController sleepController;
  private MediaLibrarySessionCallback mediaLibrarySessionCallback;
  @Nullable
  private StreamServer streamServer = null;
  @Nullable
  private LiveData<Integer> carConnectionLiveData = null;
  private CastManager castManager;

  @OptIn(markerClass = UnstableApi.class)
  @Override
  public void onCreate() {
    super.onCreate();
    Log.d(LOG_TAG, "onCreate");
    // Off the main thread: reads system CA certificates from disk
    new Thread(SessionDevice::warmUpHttpDataSourceFactory).start();
    // Create RadioPlayer and MediaLibrarySession
    radioPlayer = new RadioPlayer(radioPlayerCommands, getString(R.string.remote));
    final NotificationChannels notificationChannels = new NotificationChannels(this, LOG_TAG);
    sleepController = new SleepController(notificationChannels, sleepControllerListener);
    mediaLibrarySessionCallback = new MediaLibrarySessionCallback(this, sleepController, radio -> play(radio, true));
    mediaLibrarySession = new MediaLibraryService.MediaLibrarySession.Builder(this, radioPlayer, mediaLibrarySessionCallback)
      .setSessionActivity(notificationChannels.getContentIntent())
      .build();
    setMediaNotificationProvider(notificationChannels.getMediaNotificationProvider());
    // No notification without session device (STOPPED, ERROR are STATE_IDLE)
    setShowNotificationForIdlePlayer(SHOW_NOTIFICATION_FOR_IDLE_PLAYER_NEVER);
    // Bind to UPnP service
    if (!bindService(new Intent(this, AndroidUpnpService.class), upnpConnection, BIND_AUTO_CREATE)) {
      Log.e(LOG_TAG, "Internal failure; AndroidUpnpService not bound");
    }
    // Android Auto detection
    carConnectionLiveData = new CarConnection(this).getType();
    carConnectionLiveData.observeForever(carConnectionObserver);
    // Cast
    castManager = CastManager.getInstance();
    castManager.setContext(this, castManagerCallback);
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
    // Stop and release sessionDevice before super.onDestroy()
    if (sessionDevice != null) {
      sessionDevice.stop();
    }
    releaseSessionDevice();
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
    // Sleep notification; player notification commands are handled by Media3
    if ((intent != null) && ACTION_SLEEP_CANCEL.equals(intent.getAction())) {
      sleepController.release();
    }
    return super.onStartCommand(intent, flags, startId);
  }

  @Override
  public void onTaskRemoved(@Nullable Intent rootIntent) {
    super.onTaskRemoved(rootIntent);
    stopSelf();
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
    play(radio, false);
  }

  private void play(@NonNull Radio radio, boolean isAlarm) {
    Log.d(LOG_TAG, "play: " + radio.getName() + " => " + radio.getUri());
    Radios.setLastPlayedRadioId(this, radio.getId());
    final Radio lastRadio = (sessionDevice == null) ? null : sessionDevice.getRadio();
    if (sessionDevice != null) {
      sessionDevice.release();
    }
    sleepController.release();
    sessionDevice = createSessionDevice(radio, isAlarm);
    mediaLibrarySession.setSessionExtras(new Bundle());
    radioPlayer.set(radio, sessionDevice.isRemote(), (radio == lastRadio));
    sessionDevice.launch();
  }

  private void releaseSessionDevice() {
    if (sessionDevice != null) {
      sessionDevice.release();
      sessionDevice = null;
      radioPlayer.unset();
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
  private SessionDevice createSessionDevice(@NonNull Radio radio, boolean isAlarm) {
    SessionDevice result = null;
    if (!isAndroidAutoConnected && (streamServer != null) && new NetworkProxy(this).isOnLan()) {
      final Device upnpSelectedDevice = (upnpService == null) ? null : upnpService.getActiveSelectedDevice();
      if (castManager.hasCastSession()) {
        result = castManager.getCastSessionDevice(
          this,
          sessionDeviceListener,
          radio,
          this::play,
          streamServer);
      } else if (upnpSelectedDevice != null) {
        result = new UpnpSessionDevice(
          this,
          Radios.getAppPreferences(this).getBoolean(getString(R.string.key_pcm_mode), KEY_PCM_MODE_DEFAULT),
          sessionDeviceListener,
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
        sessionDeviceListener,
        radio,
        isAlarm);
    }
    Log.d(LOG_TAG, "createSessionDevice: " + result.getClass().getSimpleName() + " - " + result.getLockKey());
    return result;
  }
}