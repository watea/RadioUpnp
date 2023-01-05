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
import com.watea.radio_upnp.model.RadioLibrary;
import com.watea.radio_upnp.model.UpnpDevice;

import org.fourthline.cling.android.AndroidUpnpService;
import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.model.meta.RemoteDevice;

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
  private AndroidUpnpService androidUpnpService = null;
  private MediaSessionCompat session;
  private RadioLibrary radioLibrary = null;
  private HttpService.HttpServer httpServer = null;
  private final ServiceConnection httpConnection = new ServiceConnection() {
    @Override
    public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
      final HttpService.Binder httpServiceBinder = (HttpService.Binder) iBinder;
      // Retrieve HTTP server
      httpServer = httpServiceBinder.getHttpServer();
      // Bind to RadioHandler
      httpServer.bindRadioHandler(RadioService.this, radioLibrary::getFrom);
      // Retrieve UPnP service
      androidUpnpService = httpServiceBinder.getAndroidUpnpService();
      if (androidUpnpService != null) {
        upnpActionController = new UpnpActionController(androidUpnpService);
      }
    }

    // Release resources
    @Override
    public void onServiceDisconnected(ComponentName componentName) {
      httpServer = null;
      androidUpnpService = null;
      if (upnpActionController != null) {
        upnpActionController.release(false);
        upnpActionController = null;
      }
    }
  };
  private PlayerAdapter playerAdapter = null;
  private final VolumeProviderCompat volumeProviderCompat =
    new VolumeProviderCompat(VolumeProviderCompat.VOLUME_CONTROL_RELATIVE, 100, 50) {
      @Override
      public void onAdjustVolume(int direction) {
        playerAdapter.adjustVolume(direction);
      }
    };
  private boolean isAllowedToRewind = false;
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
    radioLibrary = new RadioLibrary(this);
    // Bind to HTTP service, connection will bind to UPnP service
    if (!bindService(new Intent(this, HttpService.class), httpConnection, BIND_AUTO_CREATE)) {
      Log.e(LOG_TAG, "Internal failure; HttpService not bound");
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
    return new BrowserRoot(EMPTY_MEDIA_ROOT_ID, null);
  }

  // Not used by app
  @Override
  public void onLoadChildren(
    @NonNull final String parentMediaId,
    @NonNull final Result<List<MediaBrowserCompat.MediaItem>> result) {
  }

  @Override
  public void onNewInformation(
    @NonNull final String information,
    @Nullable final String rate,
    @NonNull final String lockKey) {
    // We add current radio information to current media data
    handler.post(() -> {
      if (hasLockKey(lockKey) && (radio != null)) {
        // Rate in extras
        if (rate != null) {
          final Bundle extras = mediaController.getExtras();
          extras.putString(getString(R.string.key_rate), rate);
          session.setExtras(extras);
        }
        // Media information in ARTIST and SUBTITLE
        session.setMetadata(radio.getMediaMetadataBuilder()
          .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_URI, getPackageName())
          .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, information)
          .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, information)
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
            startForeground(NOTIFICATION_ID, getNotification());
            break;
          case PlaybackStateCompat.STATE_PAUSED:
            // No relaunch on pause
            isAllowedToRewind = false;
            notificationManager.notify(NOTIFICATION_ID, getNotification());
            break;
          case PlaybackStateCompat.STATE_ERROR:
            // For user convenience, session is kept alive
            playerAdapter.release();
            if (httpServer != null) {
              httpServer.resetRadioHandlerController();
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
                    Log.i(LOG_TAG, "Relaunch failed");
                  }
                },
                4000);
            } else {
              notificationManager.notify(NOTIFICATION_ID, getNotification());
            }
            break;
          default:
            if (playerAdapter != null) {
              playerAdapter.release();
            }
            if (httpServer != null) {
              httpServer.resetRadioHandlerController();
            }
            session.setMetadata(null);
            session.setActive(false);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            isAllowedToRewind = false;
        }
      }
    });
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    Log.d(LOG_TAG, "onDestroy: requested...");
    // Stop player to be clean on resources (if not, audio focus is not well handled)
    if (playerAdapter != null) {
      playerAdapter.stop();
    }
    // Release HTTP service
    unbindService(httpConnection);
    // Force disconnection to release resources
    httpConnection.onServiceDisconnected(null);
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
    final int[] actions012 = {0, 1, 2};
    final int[] actions0123 = {0, 1, 2, 3};
    final androidx.media.app.NotificationCompat.MediaStyle mediaStyle =
      new androidx.media.app.NotificationCompat.MediaStyle().setMediaSession(getSessionToken());
    final PlaybackStateCompat playbackStateCompat = mediaController.getPlaybackState();
    if (playbackStateCompat == null) {
      builder.setOngoing(false);
    } else {
      builder
        .addAction(actionSkipToPrevious)
        .addAction(actionStop);
      switch (playbackStateCompat.getState()) {
        case PlaybackStateCompat.STATE_PLAYING:
          // UPnP device doesn't support PAUSE action but STOP
          if (playerAdapter instanceof LocalPlayerAdapter) {
            builder.addAction(actionPause);
            mediaStyle.setShowActionsInCompactView(actions0123);
          } else {
            mediaStyle.setShowActionsInCompactView(actions012);
          }
          builder.setOngoing(true);
          break;
        case PlaybackStateCompat.STATE_PAUSED:
          builder
            .addAction(actionPlay)
            .setOngoing(false);
          mediaStyle.setShowActionsInCompactView(actions0123);
          break;
        case PlaybackStateCompat.STATE_ERROR:
          builder
            .addAction(actionRewind)
            .setOngoing(false);
          mediaStyle.setShowActionsInCompactView(actions0123);
          break;
        default:
          builder.setOngoing(false);
          mediaStyle.setShowActionsInCompactView(actions012);
      }
      builder.addAction(actionSkipToNext);
    }
    return builder
      .setStyle(mediaStyle)
      .build();
  }

  @NonNull
  private String getMediaId() {
    return mediaController.getMetadata().getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID);
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
      // Robustness: if radio is not found, abort (should not happen as current radio
      // not allowed to be deleted)
      if (radio == null) {
        Log.d(LOG_TAG, "onPrepareFromMediaId: radio not found");
        return;
      }
      // Set actual player UPnP? Extra shall contain UPnP device UDN.
      boolean isUpnp = extras.containsKey(getString(R.string.key_upnp_device));
      final Device<?, ?, ?> chosenDevice = isUpnp ?
        getChosenDevice(extras.getString(getString(R.string.key_upnp_device))) : null;
      assert httpServer != null;
      final Uri serverUri = httpServer.getUri(RadioService.this);
      // UPnP not accepted if environment not OK => force local
      if (isUpnp &&
        ((chosenDevice == null) || (upnpActionController == null) || (serverUri == null))) {
        Log.d(LOG_TAG, "onPrepareFromMediaId: can't process UPnP device");
        isUpnp = false;
      }
      // Synchronize session data
      session.setActive(true);
      session.setMetadata(radio.getMediaMetadataBuilder().build());
      session.setExtras(extras);
      lockKey = UUID.randomUUID().toString();
      if (isUpnp) {
        playerAdapter = new UpnpPlayerAdapter(
          RadioService.this,
          RadioService.this,
          radio,
          lockKey,
          RadioHandler.getHandledUri(serverUri, radio, lockKey),
          httpServer.createLogoFile(RadioService.this, radio),
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
      // Set controller for HTTP handler
      httpServer.setRadioHandlerController(new RadioHandler.Controller() {
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
      if (playerAdapter.prepareFromMediaId()) {
        startService(new Intent(RadioService.this, RadioService.class));
      } else {
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
      mediaSessionCompatCallback.onPrepareFromMediaId(getMediaId(), mediaController.getExtras());
    }

    @Override
    public void onStop() {
      playerAdapter.stop();
      playerAdapter = null;
    }

    private Device<?, ?, ?> getChosenDevice(String identity) {
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

    // Do nothing if no radio fund
    private void skipTo(int direction) {
      final Long nextRadioId = radioLibrary.get(Long.valueOf(getMediaId()), direction);
      if (nextRadioId != null) {
        // Same extras are reused
        mediaSessionCompatCallback.onPrepareFromMediaId(
          nextRadioId.toString(), mediaController.getExtras());
      }
    }
  }
}