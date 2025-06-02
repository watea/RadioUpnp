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
import android.content.Context;
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
import com.watea.radio_upnp.upnp.ActionController;
import com.watea.radio_upnp.upnp.AndroidUpnpService;
import com.watea.radio_upnp.upnp.Device;

import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class RadioService
  extends MediaBrowserServiceCompat
  implements PlayerAdapter.Listener, RadioHandler.Listener {
  public static final String DATE = "date";
  public static final String INFORMATION = "information";
  public static final String PLAYLIST = "playlist";
  public static final String ACTION_SLEEP_SET = "ACTION_SLEEP_SET";
  public static final String ACTION_SLEEP_CANCEL = "ACTION_SLEEP_CANCEL";
  private static final String LOG_TAG = RadioService.class.getSimpleName();
  private static final int REQUEST_CODE = 501;
  private static final String MEDIA_ROOT_ID = "root_id";
  private static final String PLAYLIST_SEPARATOR = "##";
  private static final String PLAYLIST_ITEM_SEPARATOR = "&&";
  private static final int FOREGROUND_NOTIFICATION_ID = 9;
  private static final int SLEEP_TIMER_NOTIFICATION_ID = 42;
  private static final Handler handler = new Handler(Looper.getMainLooper());
  private static String CHANNEL_ID;
  private final MediaSessionCompatCallback mediaSessionCompatCallback =
    new MediaSessionCompatCallback();
  private final ActionController actionController = new ActionController();
  private final ContentProvider contentProvider = new ContentProvider();
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
  private NotificationManagerCompat notificationManager;
  @Nullable
  private Radio radio = null;
  private MediaSessionCompat session;
  private RadioHttpServer radioHttpServer;
  @Nullable
  private PlayerAdapter playerAdapter = null;
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
  @Nullable
  private String lockKey = null;
  private final RadioHandler.Controller radioHandlerController = new RadioHandler.Controller() {
    @NonNull
    @Override
    public String getKey() {
      return lockKey;
    }

    @NonNull
    @Override
    public String getContentType() {
      return playerAdapter.getContentType();
    }
  };
  @Nullable
  private ScheduledExecutorService scheduler = null;
  private NotificationCompat.Action actionPause;
  private NotificationCompat.Action actionPlay;
  private NotificationCompat.Action actionRewind;
  private NotificationCompat.Action actionSkipToNext;
  private NotificationCompat.Action actionSkipToPrevious;
  private MediaControllerCompat mediaController;

  public static boolean isValid(@NonNull Context context, @NonNull MediaMetadataCompat mediaMetadataCompat) {
    final String metadataKeyMediaId = mediaMetadataCompat.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID);
    return (metadataKeyMediaId != null) && metadataKeyMediaId.startsWith(context.getString(R.string.app_name));
  }

  @Nullable
  public static String getRadioId(@NonNull Context context, @NonNull MediaMetadataCompat mediaMetadataCompat) {
    final String metadataKeyMediaId = mediaMetadataCompat.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID);
    return (metadataKeyMediaId == null) ? null : metadataKeyMediaId.replace(context.getString(R.string.app_name), "");
  }

  @NonNull
  public static List<Map<String, String>> getPlaylist(@NonNull String playlist) {
    final List<Map<String, String>> result = new ArrayList<>();
    for (final String line : playlist.split("##")) {
      final String[] items = line.split("&&");
      final Map<String, String> map = new HashMap<>();
      // Result has 2 parts: date and information
      if (items.length == 2) {
        map.put(DATE, items[0]);
        map.put(INFORMATION, items[1]);
        result.add(map);
      }
    }
    return result;
  }

  @NonNull
  private static String addPlaylistItem(@Nullable String playlist, @NonNull String item) {
    if (item.isEmpty() || (playlist != null) && playlist.endsWith(item)) {
      return (playlist == null) ? "" : playlist;
    } else {
      final DateFormat dateFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
      final String information =
        dateFormat.format(Calendar.getInstance().getTime()) + PLAYLIST_ITEM_SEPARATOR + item;
      return ((playlist == null) || playlist.isEmpty()) ?
        information : playlist + PLAYLIST_SEPARATOR + information;
    }
  }

  @Override
  public void onCreate() {
    super.onCreate();
    Log.d(LOG_TAG, "onCreate");
    // Create radios if needed
    Radios.setInstance(this);
    // Launch HTTP server
    try {
      radioHttpServer = new RadioHttpServer(this, this);
      radioHttpServer.start();
    } catch (IOException iOException) {
      Log.e(LOG_TAG, "HTTP server creation fails", iOException);
      radioHttpServer = null;
    }
    // Create a new MediaSession and controller...
    session = new MediaSessionCompat(this, LOG_TAG);
    mediaController = session.getController();
    // Link to callback where actual media controls are called...
    session.setCallback(mediaSessionCompatCallback);
    setSessionToken(session.getSessionToken());
    session.setActive(true);
    // Notification
    CHANNEL_ID = getResources().getString(R.string.app_name) + "." + LOG_TAG;
    notificationManager = NotificationManagerCompat.from(this);
    // Cancel all notifications to handle the case where the Service was killed and
    // restarted by the system
    notificationManager.cancelAll();
    // Create the (mandatory) notification channel
    if (notificationManager.getNotificationChannel(CHANNEL_ID) == null) {
      final NotificationChannel notificationChannel = new NotificationChannel(
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
      Log.d(LOG_TAG, "Existing channel reused");
    }
    // Bind to UPnP service
    if (!bindService(
      new Intent(this, AndroidUpnpService.class), upnpConnection, BIND_AUTO_CREATE)) {
      Log.e(LOG_TAG, "Internal failure; AndroidUpnpService not bound");
    }
    // Prepare notification
    actionPause = new NotificationCompat.Action(
      R.drawable.ic_pause_white_24dp,
      getString(R.string.action_pause),
      MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PAUSE));
    actionPlay = new NotificationCompat.Action(
      R.drawable.ic_play_arrow_white_24dp,
      getString(R.string.action_play),
      MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PLAY));
    actionRewind = new NotificationCompat.Action(
      R.drawable.ic_replay_white_24dp,
      getString(R.string.action_relaunch),
      MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_REWIND));
    actionSkipToNext = new NotificationCompat.Action(
      R.drawable.ic_skip_next_white_24dp,
      getString(R.string.action_skip_to_next),
      MediaButtonReceiver.buildMediaButtonPendingIntent(
        this, PlaybackStateCompat.ACTION_SKIP_TO_NEXT));
    actionSkipToPrevious = new NotificationCompat.Action(
      R.drawable.ic_skip_previous_white_24dp,
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
    if (radioHttpServer != null) {
      try {
        radioHttpServer.stop();
      } catch (IOException iOException) {
        Log.e(LOG_TAG, "HTTP server stop fails");
      }
    }
    // Release UPnP service
    unbindService(upnpConnection);
    // Finally session
    session.setActive(false);
    session.release();
  }

  @NonNull
  @Override
  public BrowserRoot onGetRoot(
    @NonNull String clientPackageName, int clientUid, @Nullable Bundle rootHints) {
    return new BrowserRoot(MEDIA_ROOT_ID, null);
  }

  @Override
  public void onLoadChildren(@NonNull final String parentMediaId, @NonNull final Result<List<MediaBrowserCompat.MediaItem>> result) {
    if (MEDIA_ROOT_ID.equals(parentMediaId)) {
      final List<MediaBrowserCompat.MediaItem> mediaItems = new ArrayList<>();
      for (Radio radio : Radios.getInstance()) {
        final MediaDescriptionCompat description = new MediaDescriptionCompat.Builder()
          .setMediaId(getString(R.string.app_name) + radio.getId())
          .setTitle(radio.getName())
          .setIconBitmap(radio.getIcon())
          .build();
        final MediaBrowserCompat.MediaItem item = new MediaBrowserCompat.MediaItem(description, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE);
        mediaItems.add(item);
      }
      result.sendResult(mediaItems);
    } else {
      result.sendResult(null);
    }
  }

  @Override
  public void onNewInformation(@NonNull final String information, @NonNull final String lockKey) {
    runIfLocked(lockKey, () -> {
      if (radio != null) {
        // Media information in ARTIST and SUBTITLE.
        // Ensure session meta data is tagged to ensure only session based use.
        final MediaMetadataCompat mediaMetadataCompat = session.getController().getMetadata();
        if (mediaMetadataCompat != null) {
          final String playlist = mediaMetadataCompat.getString(PLAYLIST);
          session.setMetadata(getTaggedMediaMetadataBuilder()
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, information)
            .putString(PLAYLIST, addPlaylistItem(playlist, information))
            .build());
          // Update UPnP
          if (playerAdapter instanceof UpnpPlayerAdapter) {
            ((UpnpPlayerAdapter) playerAdapter).onNewInformation(information);
          }
          // Update notification
          buildNotification();
        }
      }
    });
  }

  // Should be called at beginning of reading for proper display
  @Override
  public void onNewRate(@Nullable final String rate, @NonNull final String lockKey) {
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
    @NonNull final PlaybackStateCompat state, @Nullable final String lockKey) {
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
              FOREGROUND_NOTIFICATION_ID,
              getNotification(),
              playerAdapter instanceof LocalPlayerAdapter ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK : ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
          } else {
            startForeground(FOREGROUND_NOTIFICATION_ID, getNotification());
          }
          break;
        case PlaybackStateCompat.STATE_PAUSED:
          // Scheduler released, if any
          releaseScheduler();
          // No relaunch on pause
          isAllowedToRewind = false;
          buildNotification();
          break;
        case PlaybackStateCompat.STATE_ERROR:
          // Scheduler released, if any
          releaseScheduler();
          // For user convenience, session is kept alive
          if (playerAdapter != null) {
            playerAdapter.release();
          }
          if (radioHttpServer != null) {
            radioHttpServer.resetRadioHandlerController();
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
                  Log.d(LOG_TAG, "Relaunch failed, we stop");
                  mediaSessionCompatCallback.onStop();
                }
              },
              4000);
          } else {
            buildNotification();
          }
          break;
        default:
          // Release everything
          releaseScheduler();
          if (playerAdapter != null) {
            playerAdapter.release();
          }
          if (radioHttpServer != null) {
            radioHttpServer.resetRadioHandlerController();
          }
          session.setMetadata(null);
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

  @NonNull
  private MediaMetadataCompat.Builder getTaggedMediaMetadataBuilder() {
    return (radio == null) ?
      new MediaMetadataCompat.Builder() :
      radio.getMediaMetadataBuilder(
        getString(R.string.app_name),
        playerAdapter instanceof UpnpPlayerAdapter ? " " + getString(R.string.remote) : "");
  }

  @SuppressLint("SwitchIntDef")
  @NonNull
  private Notification getNotification() {
    final NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_mic_white_24dp)
      // Pending intent that is fired when user clicks on notification
      .setContentIntent(PendingIntent.getActivity(
        this,
        REQUEST_CODE,
        new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE))
      // When notification is deleted (when playback is paused and notification can be
      // deleted), fire MediaButtonPendingIntent with ACTION_STOP
      .setDeleteIntent(MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_STOP))
      // Show controls on lock screen even when user hides sensitive content
      .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
      .setSilent(true);
    final MediaMetadataCompat mediaMetadataCompat = mediaController.getMetadata();
    if (mediaMetadataCompat == null) {
      Log.e(LOG_TAG, "Internal failure; no metadata defined for radio");
    } else {
      final MediaDescriptionCompat description = mediaMetadataCompat.getDescription();
      builder
        .setLargeIcon(description.getIconBitmap())
        // Title, radio name
        .setContentTitle(description.getTitle())
        // Radio current track
        .setContentText(description.getSubtitle())
        // Remote?
        .setSubText(playerAdapter instanceof UpnpPlayerAdapter ? getString(R.string.remote) : "");
    }
    final androidx.media.app.NotificationCompat.MediaStyle mediaStyle =
      new androidx.media.app.NotificationCompat.MediaStyle().setMediaSession(getSessionToken());
    final PlaybackStateCompat playbackStateCompat = mediaController.getPlaybackState();
    if (playbackStateCompat == null) {
      builder.setOngoing(false);
    } else {
      builder.addAction(actionSkipToPrevious);
      switch (playbackStateCompat.getState()) {
        case PlaybackStateCompat.STATE_PLAYING:
          builder
            .addAction(actionPause)
            .setOngoing(true);
          mediaStyle.setShowActionsInCompactView(0, 1, 2);
          break;
        case PlaybackStateCompat.STATE_PAUSED:
          builder
            .addAction(actionPlay)
            .setOngoing(false);
          mediaStyle.setShowActionsInCompactView(0, 1, 2);
          break;
        case PlaybackStateCompat.STATE_ERROR:
          builder
            .addAction(actionRewind)
            .setOngoing(false);
          mediaStyle.setShowActionsInCompactView(0, 1, 2);
          break;
        default:
          builder.setOngoing(false);
          mediaStyle.setShowActionsInCompactView(0, 1);
      }
      builder.addAction(actionSkipToNext);
    }
    return builder
      .setStyle(mediaStyle)
      .build();
  }

  private void showSleepTimerNotification(int minutes) {
    try {
      final NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_mic_white_24dp)
        .setContentTitle(getString(R.string.sleep_timer_title))
        .setContentText(getString(R.string.sleep_timer_set_for, minutes))
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .addAction(new NotificationCompat.Action(
          R.drawable.ic_stop_white_24dp,
          getString(R.string.cancel_sleep_timer),
          MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PAUSE)));
      notificationManager.notify(SLEEP_TIMER_NOTIFICATION_ID, builder.build());
    } catch (SecurityException securityException) {
      Log.e(LOG_TAG, "Permission denied to post sleep timer notification", securityException);
    }
  }

  private void cancelSleepTimerNotification() {
    notificationManager.cancel(SLEEP_TIMER_NOTIFICATION_ID);
  }

  private void buildNotification() {
    try {
      notificationManager.notify(FOREGROUND_NOTIFICATION_ID, getNotification());
    } catch (SecurityException securityException) {
      Log.e(LOG_TAG, "Internal failure; notification not allowed");
    }
  }

  private void runIfLocked(@Nullable final String lockKey, @NonNull final Runnable runnable) {
    handler.post(() -> {
      if (session.isActive() && (lockKey != null) && lockKey.equals(this.lockKey)) {
        runnable.run();
      }
    });
  }

  private void setSleepOn(boolean isSleepOn) {
    if (mediaController != null) {
      final Bundle mediaControllerExtras = mediaController.getExtras();
      mediaControllerExtras.putBoolean(getString(R.string.key_sleep_set), isSleepOn);
      session.setExtras(mediaControllerExtras);
    }
  }

  private void releaseScheduler() {
    if (scheduler != null) {
      scheduler.shutdownNow();
      setSleepOn(false);
      cancelSleepTimerNotification();
    }
  }

  // PlayerAdapter from session for actual media controls
  private class MediaSessionCompatCallback extends MediaSessionCompat.Callback {
    // mediaId == null => last played radio
    @Override
    public void onPlayFromMediaId(@NonNull String mediaId, @NonNull Bundle extras) {
      Log.d(LOG_TAG, "onPlayFromMediaId");
      // Ensure robustness
      if (upnpService != null) {
        upnpService.getActionController().release();
        // If from alarm, we must ensure UPnP is not used
        if (extras.getBoolean(getString(R.string.key_alarm_radio), false)) {
          upnpService.setSelectedDeviceIdentity(null);
        }
      }
      // Stop player to be clean on resources (if not, audio focus is not well handled)
      if (playerAdapter != null) {
        playerAdapter.stop();
      }
      // Stop scheduler if any
      releaseScheduler();
      // Try to retrieve radio
      final Radio previousRadio = radio;
      radio = Radios.getInstance().getRadioFromId(mediaId);
      if (radio == null) {
        Log.e(LOG_TAG, "onPlayFromMediaId: radio not found");
        return;
      }
      // Catch catastrophic failure
      if (radioHttpServer == null) {
        Log.e(LOG_TAG, "onPlayFromMediaId: radioHttpServer is null");
        return;
      }
      // UPnP not accepted if environment not OK: force local processing
      final Uri serverUri = radioHttpServer.getUri();
      final Device selectedDevice = ((serverUri == null) || (upnpService == null)) ? null : upnpService.getSelectedDevice();
      // Set playerAdapter
      lockKey = UUID.randomUUID().toString();
      if (selectedDevice == null) {
        playerAdapter = new LocalPlayerAdapter(
          RadioService.this,
          RadioService.this,
          radio,
          lockKey,
          RadioHandler.getHandledUri(radioHttpServer.getLoopbackUri(), radio, lockKey));
        session.setPlaybackToLocal(AudioManager.STREAM_MUSIC);
      } else {
        playerAdapter = new UpnpPlayerAdapter(
          RadioService.this,
          RadioService.this,
          radio,
          lockKey,
          RadioHandler.getHandledUri(serverUri, radio, lockKey),
          radioHttpServer.createLogoFile(radio),
          selectedDevice,
          actionController,
          contentProvider);
        session.setPlaybackToRemote(volumeProviderCompat);
      }
      // Synchronize session data
      if (radio != previousRadio) {
        session.setExtras(new Bundle());
        session.setMetadata(getTaggedMediaMetadataBuilder().build());
      }
      // Set controller for HTTP handler
      radioHttpServer.setRadioHandlerController(radioHandlerController);
      // Start service, must be done while activity has foreground
      isAllowedToRewind = false;
      if (playerAdapter.prepareFromMediaId()) {
        startForegroundService(new Intent(RadioService.this, RadioService.class));
      } else {
        playerAdapter.stop();
        Log.d(LOG_TAG, "onPlayFromMediaId: playerAdapter.prepareFromMediaId failed");
      }
    }

    @Override
    public void onPlay() {
      assert radio != null;
      onPlayFromMediaId(radio);
    }

    @Override
    public void onPause() {
      if (playerAdapter != null) {
        playerAdapter.pause();
      }
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
      onPlay();
    }

    @Override
    public void onStop() {
      if (playerAdapter != null) {
        playerAdapter.stop();
        playerAdapter = null;
        radio = null;
      }
    }

    @Override
    public void onCustomAction(String command, Bundle extras) {
      switch (command) {
        case ACTION_SLEEP_CANCEL:
          releaseScheduler();
          break;
        case ACTION_SLEEP_SET:
          final int minutes = extras.getInt(getString(R.string.key_sleep));
          scheduler = Executors.newScheduledThreadPool(1);
          scheduler.schedule(
            () -> new Handler(Looper.getMainLooper()).post(this::onPause),
            minutes,
            TimeUnit.MINUTES);
          scheduler.shutdown();
          setSleepOn(true);
          showSleepTimerNotification(minutes);
          break;
        default:
          Log.e(LOG_TAG, "onCustomAction: unknown command!");
      }
    }

    private void skipTo(int direction) {
      assert radio != null;
      onPlayFromMediaId(Radios.getInstance().getRadioFrom(radio, direction));
    }

    // Same extras are reused
    private void onPlayFromMediaId(@NonNull Radio radio) {
      onPlayFromMediaId(Integer.toString(radio.hashCode()), mediaController.getExtras());
    }
  }
}