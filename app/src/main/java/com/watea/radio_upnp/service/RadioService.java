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
import android.content.pm.ServiceInfo;
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
import androidx.media.MediaBrowserServiceCompat;
import androidx.media.VolumeProviderCompat;
import androidx.media.session.MediaButtonReceiver;

import com.watea.radio_upnp.R;
import com.watea.radio_upnp.activity.MainActivity;
import com.watea.radio_upnp.adapter.LocalPlayerAdapter;
import com.watea.radio_upnp.adapter.PlayerAdapter;
import com.watea.radio_upnp.adapter.UpnpPlayerAdapter;
import com.watea.radio_upnp.model.Radio;
import com.watea.radio_upnp.model.Radios;
import com.watea.radio_upnp.model.UpnpDevice;
import com.watea.radio_upnp.upnp.UpnpService;

import org.fourthline.cling.android.AndroidUpnpService;
import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.model.meta.RemoteDevice;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

public class RadioService
  extends MediaBrowserServiceCompat
  implements PlayerAdapter.Listener, RadioHandler.Listener {
  private static final String LOG_TAG = RadioService.class.getName();
  private static final int REQUEST_CODE = 501;
  private static final String EMPTY_MEDIA_ROOT_ID = "empty_media_root_id";
  private static final int NOTIFICATION_ID = 9;
  private static final Handler handler = new Handler(Looper.getMainLooper());
  private static String CHANNEL_ID;
  private final MediaSessionCompatCallback mediaSessionCompatCallback =
    new MediaSessionCompatCallback();
  private NotificationManagerCompat notificationManager;
  private UpnpActionController upnpActionController = null;
  private Radio radio = null;
  private MediaSessionCompat session;
  private Radios radios;
  private NanoHttpServer nanoHttpServer;
  private PlayerAdapter playerAdapter = null;
  private final UpnpService upnpService = new UpnpService(new UpnpService.Callback() {
    @Override
    public void onNewDevice(@NonNull Device device) {

    }

    @Override
    public void onRemoveDevice(@NonNull Device device) {

    }
  });
  private final VolumeProviderCompat volumeProviderCompat =
    new VolumeProviderCompat(VolumeProviderCompat.VOLUME_CONTROL_RELATIVE, 100, 50) {
      @Override
      public void onAdjustVolume(int direction) {
        if (playerAdapter != null) {
          playerAdapter.adjustVolume(direction);
        }
      }
    };
  private boolean isAllowedToRewind = false;
  private String lockKey = null;
  private NotificationCompat.Action actionPause;
  private NotificationCompat.Action actionStop;
  private NotificationCompat.Action actionPlay;
  private NotificationCompat.Action actionRewind;
  private NotificationCompat.Action actionSkipToNext;
  private NotificationCompat.Action actionSkipToPrevious;
  private MediaControllerCompat mediaController;

  public static boolean isValid(@NonNull MediaMetadataCompat mediaMetadataCompat) {
    return getSessionTag()
      .equals(mediaMetadataCompat.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_URI));
  }

  @NonNull
  private static String getSessionTag() {
    final Package thisPackage = RadioService.class.getPackage();
    assert thisPackage != null;
    return thisPackage.getName();
  }

  @NonNull
  private static MediaMetadataCompat.Builder getTaggedMediaMetadataBuilder(@NonNull Radio radio) {
    return radio
      .getMediaMetadataBuilder()
      .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_URI, getSessionTag());
  }

  @Override
  public void onCreate() {
    super.onCreate();
    Log.d(LOG_TAG, "onCreate");
    // Launch HTTP server
    nanoHttpServer = new NanoHttpServer(this);
    try {
      nanoHttpServer.start();
    } catch (IOException iOException) {
      Log.d(LOG_TAG, "HTTP server creation fails", iOException);
    }
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
    // Create the (mandatory) notification channel
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
    // Radio library access
    radios = MainActivity.getRadios();
    // Bind to HTTP service
//    if (!bindService(new Intent(this, HttpService.class), httpConnection, BIND_AUTO_CREATE)) {
//      Log.e(LOG_TAG, "Internal failure; HttpService not bound");
//    }
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
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    Log.d(LOG_TAG, "onDestroy");
    // Stop player to be clean on resources (if not, audio focus is not well handled)
    if (playerAdapter != null) {
      playerAdapter.stop();
    }
    // Release HTTP service
    //unbindService(httpConnection);
    // Force disconnection to release resources
    //httpConnection.onServiceDisconnected(null);
    // Finally session
    session.release();
  }

  // Not used by app
  @NonNull
  @Override
  public BrowserRoot onGetRoot(
    @NonNull String clientPackageName, int clientUid, @Nullable Bundle rootHints) {
    return new BrowserRoot(EMPTY_MEDIA_ROOT_ID, null);
  }

  // Not used by app
  @Override
  public void onLoadChildren(
    @NonNull final String parentMediaId,
    @NonNull final Result<List<MediaBrowserCompat.MediaItem>> result) {
  }

  @Override
  public void onNewInformation(@NonNull final String information, @NonNull final String lockKey) {
    runIfLocked(lockKey, () -> {
      if (radio != null) {
        // Media information in ARTIST and SUBTITLE.
        // Ensure session meta data is tagged to ensure only session based use.
        session.setMetadata(getTaggedMediaMetadataBuilder(radio)
          .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, information)
          .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, information)
          .build());
        // Update UPnP
        if (playerAdapter instanceof UpnpPlayerAdapter) {
          ((UpnpPlayerAdapter) playerAdapter).onNewInformation(information);
        }
        // Update notification
        buildNotification();
      }
    });
  }

  // Should be called at beginning of reading for proper display
  @Override
  public void onNewRate(@Nullable final String rate, @NonNull final String lockKey) {
    // Flush previous information and ensure session meta data is tagged
    onNewInformation("", lockKey);
    // We add current rate to current media data
    runIfLocked(lockKey, () -> {
      // Rate in extras
      final Bundle extras = mediaController.getExtras();
      extras.putString(getString(R.string.key_rate), (rate == null) ? "" : rate);
      session.setExtras(extras);
      // Update notification
      buildNotification();
    });
  }

  // Only if lockKey still valid
  @SuppressLint("SwitchIntDef")
  @Override
  public void onPlaybackStateChange(
    @NonNull final PlaybackStateCompat state, @NonNull final String lockKey) {
    runIfLocked(lockKey, () -> {
      Log.d(LOG_TAG, "Valid state/lock key received: " + state.getState() + "/" + lockKey);
      // Report the state to the MediaSession
      session.setPlaybackState(state);
      // Manage the started state of this service, and session activity
      switch (state.getState()) {
        case PlaybackStateCompat.STATE_PLAYING:
          // Relaunch now allowed
          isAllowedToRewind = true;
        case PlaybackStateCompat.STATE_BUFFERING:
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
              NOTIFICATION_ID,
              getNotification(),
              ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST);
          } else {
            startForeground(NOTIFICATION_ID, getNotification());
          }
          break;
        case PlaybackStateCompat.STATE_PAUSED:
          // No relaunch on pause
          isAllowedToRewind = false;
          buildNotification();
          break;
        case PlaybackStateCompat.STATE_ERROR:
          // For user convenience, session is kept alive
          if (playerAdapter != null) {
            playerAdapter.release();
          }
          if (nanoHttpServer != null) {
            nanoHttpServer.resetRadioHandlerController();
          }
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
                  Log.i(LOG_TAG, "Relaunch failed, we stop");
                  mediaSessionCompatCallback.onStop();
                }
              },
              4000);
          } else {
            buildNotification();
          }
          break;
        default:
          if (playerAdapter != null) {
            playerAdapter.release();
          }
          if (nanoHttpServer != null) {
            nanoHttpServer.resetRadioHandlerController();
          }
          session.setMetadata(null);
          session.setActive(false);
          stopForeground(STOP_FOREGROUND_REMOVE);
          stopSelf();
          isAllowedToRewind = false;
      }
    });
  }

  @Override
  public void onTaskRemoved(@NonNull Intent rootIntent) {
    super.onTaskRemoved(rootIntent);
    stopSelf();
  }

  @SuppressLint("SwitchIntDef")
  @NonNull
  private Notification getNotification() {
    final NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
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
    final MediaMetadataCompat mediaMetadataCompat = mediaController.getMetadata();
    if (mediaMetadataCompat == null) {
      Log.e(LOG_TAG, "getNotification: internal failure; no metadata defined for radio");
    } else {
      final MediaDescriptionCompat description = mediaMetadataCompat.getDescription();
      builder
        .setLargeIcon(description.getIconBitmap())
        // Title, radio name
        .setContentTitle(description.getTitle())
        // Radio current track
        .setContentText(description.getSubtitle());
    }
    final androidx.media.app.NotificationCompat.MediaStyle mediaStyle =
      new androidx.media.app.NotificationCompat.MediaStyle().setMediaSession(getSessionToken());
    final PlaybackStateCompat playbackStateCompat = mediaController.getPlaybackState();
    if (playbackStateCompat == null) {
      builder.setOngoing(false);
    } else {
      mediaStyle.setShowActionsInCompactView(0, 1, 2);
      builder.addAction(actionSkipToPrevious);
      switch (playbackStateCompat.getState()) {
        case PlaybackStateCompat.STATE_PLAYING:
          // UPnP device doesn't support PAUSE action but STOP
          builder
            .addAction((playerAdapter instanceof LocalPlayerAdapter) ? actionPause : actionStop)
            .setOngoing(true);
          break;
        case PlaybackStateCompat.STATE_PAUSED:
          builder
            .addAction(actionPlay)
            .setOngoing(false);
          break;
        case PlaybackStateCompat.STATE_ERROR:
          builder
            .addAction(actionRewind)
            .setOngoing(false);
          break;
        default:
          builder
            .addAction(actionStop)
            .setOngoing(false);
      }
      builder.addAction(actionSkipToNext);
    }
    return builder
      .setStyle(mediaStyle)
      .build();
  }

  @Nullable
  private Device<?, ?, ?> getChosenDevice(@NonNull String identity) {
    if (androidUpnpService == null) {
      return null;
    }
    for (RemoteDevice remoteDevice : androidUpnpService.getRegistry().getRemoteDevices()) {
      if (UpnpDevice.getIdentity(remoteDevice).equals(identity)) {
        return remoteDevice;
      }
      // Embedded devices?
      final RemoteDevice[] remoteDevices = remoteDevice.getEmbeddedDevices();
      if (remoteDevices != null) {
        for (RemoteDevice embeddedRemoteDevice : remoteDevices) {
          if (UpnpDevice.getIdentity(embeddedRemoteDevice).equals(identity)) {
            return embeddedRemoteDevice;
          }
        }
      }
    }
    return null;
  }

  private void buildNotification() {
    try {
      notificationManager.notify(NOTIFICATION_ID, getNotification());
    } catch (SecurityException securityException) {
      Log.e(LOG_TAG, "Notification not allowed");
    }
  }

  private void runIfLocked(@NonNull final String lockKey, @NonNull final Runnable runnable) {
    handler.post(() -> {
      if (session.isActive() && lockKey.equals(this.lockKey)) {
        runnable.run();
      }
    });
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
      // Try to retrieve radio
      try {
        radio = radios.getRadioFrom(mediaId);
        if (radio == null) {
          abort("onPrepareFromMediaId: radio not found");
          return;
        }
      } catch (Exception exception) {
        abort("onPrepareFromMediaId: radioLibrary error; " + exception);
        return;
      }
      final String identity = extras.getString(getString(R.string.key_upnp_device));
      final Device<?, ?, ?> chosenDevice = (identity == null) ? null : getChosenDevice(identity);
      assert nanoHttpServer != null;
      final Uri serverUri = nanoHttpServer.getUri();
      // UPnP not accepted if environment not OK: force STOP
      if ((identity != null) &&
        ((chosenDevice == null) || (upnpActionController == null) || (serverUri == null))) {
        abort("onPrepareFromMediaId: can't process UPnP device");
        return;
      }
      // Synchronize session data
      session.setActive(true);
      session.setExtras(extras);
      lockKey = UUID.randomUUID().toString();
      // Tag metadata with package name to enable session discrepancy
      session.setMetadata(getTaggedMediaMetadataBuilder(radio).build());
      if (identity == null) {
        playerAdapter = new LocalPlayerAdapter(
          RadioService.this,
          RadioService.this,
          radio,
          lockKey,
          RadioHandler.getHandledUri(nanoHttpServer.getLoopbackUri(), radio, lockKey));
        session.setPlaybackToLocal(AudioManager.STREAM_MUSIC);
      } else {
        playerAdapter = new UpnpPlayerAdapter(
          RadioService.this,
          RadioService.this,
          radio,
          lockKey,
          RadioHandler.getHandledUri(serverUri, radio, lockKey),
          nanoHttpServer.createLogoFile(radio),
          chosenDevice,
          upnpActionController);
        session.setPlaybackToRemote(volumeProviderCompat);
      }
      // Set controller for HTTP handler
      nanoHttpServer.setRadioHandlerController(new RadioHandler.Controller() {
        @NonNull
        @Override
        public String getKey() {
          return lockKey;
        }

        @NonNull
        @Override
        public String getContentType() {
          return (playerAdapter instanceof UpnpPlayerAdapter) ?
            ((UpnpPlayerAdapter) playerAdapter).getContentType() :
            RadioHandler.Controller.super.getContentType();
        }
      });
      // Start service, must be done while activity has foreground
      isAllowedToRewind = false;
      if (playerAdapter.prepareFromMediaId()) {
        startService(new Intent(RadioService.this, RadioService.class));
      } else {
        playerAdapter.stop();
        Log.d(LOG_TAG, "onPrepareFromMediaId: playerAdapter.prepareFromMediaId failed");
      }
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
      onPrepareFromMediaId(radio);
    }

    @Override
    public void onStop() {
      if (playerAdapter == null) {
        Log.d(LOG_TAG, "onStop: but playerAdapter is null!");
      } else {
        playerAdapter.stop();
        playerAdapter = null;
      }
    }

    private void skipTo(int direction) {
      onPrepareFromMediaId(radios.getRadioFrom(radio, direction));
    }

    // Same extras are reused
    private void onPrepareFromMediaId(@NonNull Radio radio) {
      onPrepareFromMediaId(Integer.toString(radio.hashCode()), mediaController.getExtras());
    }

    private void abort(@NonNull String log) {
      Log.d(LOG_TAG, log);
      onPlaybackStateChange(
        PlayerAdapter.getPlaybackStateCompatBuilder(PlaybackStateCompat.STATE_STOPPED).build(),
        lockKey);
    }
  }
}