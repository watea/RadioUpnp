/*
 * Copyright (c) 2018. Stephane Treuchot
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
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.media.MediaBrowserServiceCompat;
import androidx.media.VolumeProviderCompat;
import androidx.media.session.MediaButtonReceiver;

import com.watea.radio_upnp.R;
import com.watea.radio_upnp.activity.MainActivity;
import com.watea.radio_upnp.adapter.LocalPlayerAdapter;
import com.watea.radio_upnp.adapter.PlayerAdapter;
import com.watea.radio_upnp.adapter.UpnpPlayerAdapter;
import com.watea.radio_upnp.model.DlnaDevice;
import com.watea.radio_upnp.model.Radio;
import com.watea.radio_upnp.model.RadioLibrary;

import org.fourthline.cling.android.AndroidUpnpService;
import org.fourthline.cling.android.AndroidUpnpServiceImpl;
import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.model.meta.RemoteDevice;

import java.util.List;
import java.util.UUID;

public class RadioService
  extends MediaBrowserServiceCompat
  implements PlayerAdapter.Listener, RadioHandler.Listener {
  private static final String LOG_TAG = RadioService.class.getName();
  private static final int NOTIFICATION_ID = 9;
  private static final int REQUEST_CODE = 501;
  private static final Handler handler = new Handler(Looper.getMainLooper());
  private static String CHANNEL_ID;
  private final UpnpServiceConnection upnpConnection = new UpnpServiceConnection();
  private final MediaSessionCompatCallback mediaSessionCompatCallback =
    new MediaSessionCompatCallback();
  private NotificationManagerCompat notificationManager;
  private UpnpActionController upnpActionController = null;
  private Radio radio = null;
  private AndroidUpnpService androidUpnpService = null;
  private MediaSessionCompat session;
  private RadioLibrary radioLibrary = null;
  private PlayerAdapter playerAdapter = null;
  private final VolumeProviderCompat volumeProviderCompat =
    new VolumeProviderCompat(VolumeProviderCompat.VOLUME_CONTROL_RELATIVE, 100, 50) {
      @Override
      public void onAdjustVolume(int direction) {
        playerAdapter.adjustVolume(direction);
      }
    };
  private HttpServer httpServer = null;
  private MediaMetadataCompat mediaMetadataCompat = null;
  private boolean isAllowedToRewind, isForeground = false;
  private String lockKey;
  private NotificationCompat.Action actionPause;
  private NotificationCompat.Action actionStop;
  private NotificationCompat.Action actionPlay;
  private NotificationCompat.Action actionRewind;
  private NotificationCompat.Action actionSkipToNext;
  private NotificationCompat.Action actionSkipToPrevious;
  private MediaControllerCompat mediaController;

  @Override
  public void onCreate() {
    super.onCreate();
    // Create a new MediaSession and controller...
    session = new MediaSessionCompat(this, LOG_TAG);
    mediaController = session.getController();
    // Link to callback where actual media controls are called...
    session.setCallback(mediaSessionCompatCallback);
    setSessionToken(session.getSessionToken());
    // Notification
    CHANNEL_ID = getResources().getString(R.string.app_name) + ".channel";
    notificationManager = NotificationManagerCompat.from(this);
    // Cancel all notifications to handle the case where the Service was killed and
    // restarted by the system
    notificationManager.cancelAll();
    // Create the (mandatory) notification channel when running on Android Oreo and more
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      if (notificationManager.getNotificationChannel(CHANNEL_ID) == null) {
        NotificationChannel notificationChannel = new NotificationChannel(
          CHANNEL_ID,
          getString(R.string.radio_service_notification_name),
          NotificationManager.IMPORTANCE_HIGH);
        // Configure the notification channel
        notificationChannel.setDescription(getString(R.string.radio_service_description)); // User-visible
        notificationChannel.enableLights(true);
        notificationChannel.enableVibration(false);
        // Sets the notification light color for notifications posted to this
        // channel, if the device supports this feature
        notificationChannel.setLightColor(Color.GREEN);
        notificationChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        notificationManager.createNotificationChannel(notificationChannel);
        Log.d(LOG_TAG, "New channel created");
      } else {
        Log.i(LOG_TAG, "Existing channel reused");
      }
    }
    // Radio library access
    radioLibrary = new RadioLibrary(this);
    // Init HTTP Server
    httpServer = new HttpServer(
      this,
      getString(R.string.app_name),
      radioLibrary::getFrom,
      this,
      () -> {
        Log.d(LOG_TAG, "HTTP Server error");
        stopSelf();
      });
    httpServer.start();
    // Bind to UPnP service, launch if not already
    if (!bindService(
      new Intent(this, AndroidUpnpServiceImpl.class),
      upnpConnection,
      BIND_AUTO_CREATE)) {
      Log.e(LOG_TAG, "Internal failure; AndroidUpnpService not bound");
    }
    // Prepare notification
    actionPause = new NotificationCompat.Action(
      R.drawable.ic_pause_white_24dp,
      getString(R.string.action_pause),
      MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PAUSE));
    actionStop = new NotificationCompat.Action(
      R.drawable.ic_stop_white_24dp,
      getString(R.string.action_stop),
      MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_STOP));
    actionPlay = new NotificationCompat.Action(
      R.drawable.ic_play_arrow_white_24dp,
      getString(R.string.action_play),
      MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PLAY));
    actionRewind = new NotificationCompat.Action(
      R.drawable.ic_baseline_replay_24dp,
      getString(R.string.action_relaunch),
      MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_REWIND));
    actionSkipToNext = new NotificationCompat.Action(
      R.drawable.ic_baseline_skip_next_white_24dp,
      getString(R.string.action_skip_to_next),
      MediaButtonReceiver.buildMediaButtonPendingIntent(
        this, PlaybackStateCompat.ACTION_SKIP_TO_NEXT));
    actionSkipToPrevious = new NotificationCompat.Action(
      R.drawable.ic_baseline_skip_previous_white_24dp,
      getString(R.string.action_skip_to_previous),
      MediaButtonReceiver.buildMediaButtonPendingIntent(
        this, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS));
    Log.d(LOG_TAG, "onCreate: done!");
  }

  // Not used by app
  @NonNull
  @Override
  public BrowserRoot onGetRoot(
    @NonNull String clientPackageName, int clientUid, @Nullable Bundle rootHints) {
    return new BrowserRoot(radioLibrary.getRoot(), null);
  }

  // Not used by app
  @Override
  public void onLoadChildren(
    @NonNull final String parentMediaId,
    @NonNull final Result<List<MediaBrowserCompat.MediaItem>> result) {
    result.sendResult(
      parentMediaId.equals(radioLibrary.getRoot()) ? radioLibrary.getMediaItems() : null);
  }

  @Override
  public void onNewInformation(
    @NonNull final String information,
    @Nullable final String rate,
    @NonNull final String lockKey) {
    // We add current radio information to current media data
    handler.post(() -> {
      if (hasLockKey(lockKey) && (radio != null)) {
        session.setMetadata(RadioService.this.mediaMetadataCompat =
          radio.getMediaMetadataBuilder()
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, information)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, information)
            // Use WRITER for rate
            .putString(MediaMetadataCompat.METADATA_KEY_WRITER, rate)
            .build());
        // Update notification
        notificationManager.notify(NOTIFICATION_ID, getNotification());
      }
    });
  }

  // Only if lockKey still valid
  @SuppressLint("SwitchIntDef")
  @Override
  public void onPlaybackStateChange(
    @NonNull final PlaybackStateCompat state, @NonNull final String lockKey) {
    handler.post(() -> {
      if (hasLockKey(lockKey)) {
        Log.d(LOG_TAG, "New valid state/lock key received: " + state.getState() + "/" + lockKey);
        // Report the state to the MediaSession
        session.setPlaybackState(state);
        // Manage the started state of this service, and session activity
        switch (state.getState()) {
          case PlaybackStateCompat.STATE_PLAYING:
            // Relaunch now allowed
            isAllowedToRewind = true;
          case PlaybackStateCompat.STATE_BUFFERING:
            if (isForeground) {
              notificationManager.notify(NOTIFICATION_ID, getNotification());
            } else {
              startForeground(NOTIFICATION_ID, getNotification());
              isForeground = true;
            }
            break;
          case PlaybackStateCompat.STATE_PAUSED:
            // No relaunch on pause
            isAllowedToRewind = false;
            // API 30 and above doesn't allow to restart from foreground
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
              stopForeground(false);
              isForeground = false;
            }
            notificationManager.notify(NOTIFICATION_ID, getNotification());
            break;
          case PlaybackStateCompat.STATE_ERROR:
            // For user convenience in local mode, session is kept alive
            if (isForeground && (playerAdapter instanceof LocalPlayerAdapter)) {
              playerAdapter.release();
              httpServer.setRadioHandlerController(null);
              // Try to relaunch just once
              if (isAllowedToRewind) {
                isAllowedToRewind = false;
                handler.postDelayed(() -> {
                    try {
                      // Still in error?
                      if (mediaController.getPlaybackState().getState() ==
                        PlaybackStateCompat.STATE_ERROR) {
                        mediaSessionCompatCallback.onRewind();
                      }
                    } catch (Exception exception) {
                      Log.i(LOG_TAG, "Relaunch failed");
                    }
                  },
                  4000);
              } else {
                // API 30 and above doesn't allow to restart from foreground
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                  stopForeground(false);
                  isForeground = false;
                }
                notificationManager.notify(NOTIFICATION_ID, getNotification());
              }
              break;
            }
          default:
            playerAdapter.release();
            httpServer.setRadioHandlerController(null);
            session.setMetadata(mediaMetadataCompat = null);
            session.setActive(false);
            stopForeground(true);
            stopSelf();
            isForeground = isAllowedToRewind = false;
        }
      }
    });
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    Log.d(LOG_TAG, "onStartCommand");
    isForeground = false;
    if (playerAdapter == null) {
      Log.d(LOG_TAG, "onStartCommand: internal failure; playerAdapter is null!");
      stopSelf();
    } else if (!playerAdapter.prepareFromMediaId()) {
      Log.d(LOG_TAG, "onStartCommand failed to launch player");
      stopSelf();
    }
    return super.onStartCommand(intent, flags, startId);
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    Log.d(LOG_TAG, "onDestroy: requested...");
    // Stop player to be clean on resources (if not, audio focus is not well handled)
    if (playerAdapter != null) {
      playerAdapter.stop();
    }
    // Stop UPnP service
    unbindService(upnpConnection);
    // Forced disconnection
    upnpConnection.onServiceDisconnected(null);
    // Stop HTTP server, if created
    if (httpServer != null) {
      httpServer.stopServer();
    }
    // Release radioLibrary, if opened
    if (radioLibrary != null) {
      radioLibrary.close();
    }
    // Finally session
    session.release();
    Log.d(LOG_TAG, "onDestroy: done!");
  }


  @Override
  public void onTaskRemoved(@NonNull Intent rootIntent) {
    super.onTaskRemoved(rootIntent);
    stopSelf();
  }

  private boolean hasLockKey(@NonNull String lockKey) {
    return session.isActive() && lockKey.equals(this.lockKey);
  }

  @SuppressLint("SwitchIntDef")
  @NonNull
  private Notification getNotification() {
    NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
      .setSilent(true)
      .setSmallIcon(R.drawable.ic_baseline_mic_white_24dp)
      // Pending intent that is fired when user clicks on notification
      .setContentIntent(PendingIntent.getActivity(
        this,
        REQUEST_CODE,
        new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE))
      // When notification is deleted (when playback is paused and notification can be
      // deleted) fire MediaButtonPendingIntent with ACTION_STOP
      .setDeleteIntent(
        MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_STOP))
      // Show controls on lock screen even when user hides sensitive content
      .setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
    if (mediaMetadataCompat == null) {
      Log.e(LOG_TAG, "getNotification: internal failure; no metadata defined for radio");
    } else {
      MediaDescriptionCompat description = mediaMetadataCompat.getDescription();
      builder
        .setLargeIcon(description.getIconBitmap())
        // Title, radio name
        .setContentTitle(description.getTitle())
        // Radio current track
        .setContentText(description.getSubtitle());
    }
    final int[] actions01 = {0, 1};
    final int[] actions012 = {0, 1, 2};
    int[] actions = {};
    if (mediaController == null) {
      Log.e(LOG_TAG, "getNotification: internal failure; no mediaController");
      builder.setOngoing(false);
    } else {
      builder.addAction(actionSkipToPrevious);
      switch (mediaController.getPlaybackState().getState()) {
        case PlaybackStateCompat.STATE_PLAYING:
          // UPnP device doesn't support PAUSE action but STOP
          builder
            .addAction(playerAdapter instanceof UpnpPlayerAdapter ? actionStop : actionPause)
            .setOngoing(true);
          actions = actions012;
          break;
        case PlaybackStateCompat.STATE_PAUSED:
          builder
            .addAction(actionPlay)
            .setOngoing(false);
          actions = actions012;
          break;
        case PlaybackStateCompat.STATE_ERROR:
          builder
            .addAction(actionRewind)
            .setOngoing(false);
          actions = actions012;
          break;
        default:
          builder.setOngoing(false);
          actions = actions01;
      }
      builder.addAction(actionSkipToNext);
    }
    builder.setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
      .setMediaSession(getSessionToken())
      .setShowActionsInCompactView(actions));
    return builder.build();
  }

  @NonNull
  private String getMediaId() {
    return mediaController.getMetadata().getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID);
  }

  private class UpnpServiceConnection implements ServiceConnection {
    @Override
    public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
      androidUpnpService = (AndroidUpnpService) iBinder;
      if (androidUpnpService != null) {
        upnpActionController = new UpnpActionController(androidUpnpService);
      }
    }

    @Override
    public void onServiceDisconnected(ComponentName componentName) {
      androidUpnpService = null;
      if (upnpActionController != null) {
        upnpActionController.release(false);
        upnpActionController = null;
      }
    }
  }

  // PlayerAdapter from session for actual media controls
  private class MediaSessionCompatCallback extends MediaSessionCompat.Callback {
    @Override
    public void onPrepareFromMediaId(@NonNull String mediaId, @NonNull Bundle extras) {
      // Ensure robustness
      if (upnpActionController != null) {
        upnpActionController.release(true);
      }
      // Stop player to be clean on resources (if not, audio focus is not well handled)
      if (playerAdapter != null) {
        playerAdapter.stop();
      }
      // Radio retrieved in database
      radio = radioLibrary.getFrom(Long.valueOf(mediaId));
      // If radio is not found, abort (may happen if radio were deleted by user)
      if (radio == null) {
        Log.d(LOG_TAG, "onPrepareFromMediaId: radio not found");
        return;
      }
      // Set actual player DLNA? Extra shall contain DLNA device UDN.
      boolean isDlna = extras.containsKey(getString(R.string.key_dlna_device));
      Device<?, ?, ?> chosenDevice = isDlna ?
        getChosenDevice(extras.getString(getString(R.string.key_dlna_device))) : null;
      Uri serverUri = httpServer.getUri();
      // Robustness for UPnP mode; test if environment is still OK
      if (isDlna &&
        ((chosenDevice == null) || (upnpActionController == null) || (serverUri == null))) {
        Log.e(LOG_TAG, "onPrepareFromMediaId: internal failure; can't process DLNA device");
        return;
      }
      // Synchronize session data
      session.setActive(true);
      session.setMetadata(mediaMetadataCompat = radio.getMediaMetadataBuilder().build());
      session.setExtras(extras);
      lockKey = UUID.randomUUID().toString();
      if (isDlna) {
        playerAdapter = new UpnpPlayerAdapter(
          RadioService.this,
          RadioService.this,
          radio,
          lockKey,
          RadioHandler.getHandledUri(serverUri, radio, lockKey),
          httpServer.createLogoFile(radio),
          chosenDevice,
          upnpActionController);
        session.setPlaybackToRemote(volumeProviderCompat);
      } else {
        playerAdapter = new LocalPlayerAdapter(
          RadioService.this,
          RadioService.this,
          radio,
          lockKey,
          RadioHandler.getHandledUri(httpServer.getLoopbackUri(), radio, lockKey));
        session.setPlaybackToLocal(AudioManager.STREAM_MUSIC);
      }
      // PlayerAdapter controls radio stream
      httpServer.setRadioHandlerController(playerAdapter);
      // Start service, must be done while activity has foreground
      ContextCompat.startForegroundService(
        RadioService.this, new Intent(RadioService.this, RadioService.class));
    }

    @Override
    public void onPlay() {
      playerAdapter.play();
    }

    @Override
    public void onPause() {
      playerAdapter.pause();
    }

    @Override
    public void onSkipToNext() {
      skipTo(1);
    }

    @Override
    public void onSkipToPrevious() {
      skipTo(-1);
    }

    @Override
    public void onRewind() {
      mediaSessionCompatCallback.onPrepareFromMediaId(getMediaId(), mediaController.getExtras());
    }

    @Override
    public void onStop() {
      playerAdapter.stop();
    }

    private Device<?, ?, ?> getChosenDevice(String identity) {
      if (androidUpnpService == null) {
        return null;
      }
      for (RemoteDevice remoteDevice : androidUpnpService.getRegistry().getRemoteDevices()) {
        if (DlnaDevice.getIdentity(remoteDevice).equals(identity)) {
          return remoteDevice;
        }
        // Embedded devices?
        RemoteDevice[] remoteDevices = remoteDevice.getEmbeddedDevices();
        if (remoteDevices != null) {
          for (RemoteDevice embeddedRemoteDevice : remoteDevices) {
            if (DlnaDevice.getIdentity(embeddedRemoteDevice).equals(identity)) {
              return embeddedRemoteDevice;
            }
          }
        }
      }
      return null;
    }

    // Do nothing if no active session or no radio fund
    private void skipTo(int direction) {
      if (mediaController != null) {
        Bundle extras = mediaController.getExtras();
        Long nextRadioId = radioLibrary.get(
          Long.valueOf(getMediaId()),
          extras.getBoolean(getString(R.string.key_preferred_radios)),
          direction);
        if (nextRadioId != null) {
          // Same extras are reused
          mediaSessionCompatCallback.onPrepareFromMediaId(nextRadioId.toString(), extras);
        }
      }
    }
  }
}