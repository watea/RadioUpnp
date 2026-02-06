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
import com.watea.radio_upnp.adapter.PlayerAdapter;
import com.watea.radio_upnp.model.LocalSessionDevice;
import com.watea.radio_upnp.model.Radio;
import com.watea.radio_upnp.model.Radios;
import com.watea.radio_upnp.model.UpnpSessionDevice;
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
import java.util.function.Consumer;

public class RadioService
  extends MediaBrowserServiceCompat
  implements PlayerAdapter.StateController, RadioHandler.Listener {
  public static final String DATE = "date";
  public static final String INFORMATION = "information";
  public static final String PLAYLIST = "playlist";
  public static final String ACTION_SLEEP_SET = "ACTION_SLEEP_SET";
  public static final String ACTION_SLEEP_CANCEL = "ACTION_SLEEP_CANCEL";
  public static final String ACTION_RELOAD = "ACTION_RELOAD";
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
  private final ContentProvider contentProvider = new ContentProvider();
  private final Radios.Listener radiosListener = new Radios.Listener() {
    @Override
    public void onPreferredChange() {
      notifyChildrenChanged(MEDIA_ROOT_ID);
    }
  };
  private PlayerAdapter playerAdapter;
  private final VolumeProviderCompat volumeProviderCompat =
    new VolumeProviderCompat(VolumeProviderCompat.VOLUME_CONTROL_RELATIVE, 100, 50) {
      @Override
      public void onAdjustVolume(int direction) {
        playerAdapter.adjustVolume(direction);
      }
    };
  private final Consumer<Integer> sessionDeviceListener = state -> playerAdapter.notifyState(state);
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
  private MediaSessionCompat session;
  private RadioHttpServer radioHttpServer;
  private boolean isAllowedToRewind = false;
  @Nullable
  private String lockKey = null;
  private final RadioHandler.Controller radioHandlerController = new RadioHandler.Controller() {
    @NonNull
    @Override
    public String getContentType() {
      return playerAdapter.getContentType();
    }

    @Override
    public boolean isActiv(@NonNull String lockKey) {
      if (lockKey.equals(RadioService.this.lockKey)) {
        final int state = (session == null) ? PlaybackStateCompat.STATE_ERROR : session.getController().getPlaybackState().getState();
        return !((state == PlaybackStateCompat.STATE_ERROR) || (state == PlaybackStateCompat.STATE_PAUSED) || (state == PlaybackStateCompat.STATE_STOPPED));
      }
      return false;
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
    Radios.setInstance(this, null);
    Radios.getInstance().addListener(radiosListener);
    // Launch HTTP server
    try {
      radioHttpServer = new RadioHttpServer(this, this, radioHandlerController);
      radioHttpServer.start();
    } catch (IOException iOException) {
      Log.e(LOG_TAG, "HTTP server creation fails", iOException);
      radioHttpServer = null;
    }
    // Player
    playerAdapter = new PlayerAdapter(this, this);
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
    playerAdapter.stop();
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
  public BrowserRoot onGetRoot(@NonNull String clientPackageName, int clientUid, @Nullable Bundle rootHints) {
    Log.d(LOG_TAG, "onGetRoot: with clientPackageName = " + clientPackageName);
    return new BrowserRoot(MEDIA_ROOT_ID, null);
  }

  @Override
  public void onLoadChildren(@NonNull final String parentMediaId, @NonNull final Result<List<MediaBrowserCompat.MediaItem>> result) {
    Log.d(LOG_TAG, "onLoadChildren: with parentMediaId = " + parentMediaId);
    final List<MediaBrowserCompat.MediaItem> mediaItems = new ArrayList<>();
    if (MEDIA_ROOT_ID.equals(parentMediaId)) {
      for (final Radio radio : Radios.getInstance().getActuallySelectedRadios()) {
        final String radioId = radio.getId();
        Log.d(LOG_TAG, "Children: Id = " + radioId);
        final MediaDescriptionCompat description = new MediaDescriptionCompat.Builder()
          .setMediaId(radioId)
          .setTitle(radio.getName())
          .setIconBitmap(radio.getIcon())
          .build();
        final MediaBrowserCompat.MediaItem item = new MediaBrowserCompat.MediaItem(description, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE);
        mediaItems.add(item);
      }
    }
    result.sendResult(mediaItems);
  }

  @Override
  public void onNewInformation(@NonNull final String information, @NonNull final String lockKey) {
    runIfLocked(lockKey, () -> {
      final Radio radio = playerAdapter.getRadio();
      if (radio != null) {
        final MediaMetadataCompat mediaMetadataCompat = session.getController().getMetadata();
        if (mediaMetadataCompat != null) {
          buildSessionMetadata(radio, information, addPlaylistItem(mediaMetadataCompat.getString(PLAYLIST), information));
          playerAdapter.onNewInformation(information);
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
  public void onPlaybackStateChange(@NonNull final PlaybackStateCompat state, @Nullable final String lockKey) {
    runIfLocked(lockKey, () -> {
      Log.d(LOG_TAG, "Valid state/lock key received: " + state.getState() + "/" + lockKey);
      // Report the state to the MediaSession
      session.setPlaybackState(state);
      // Manage the started state of this service, and session activity
      switch (state.getState()) {
        case PlaybackStateCompat.STATE_PLAYING:
          isAllowedToRewind = true; // Relaunch now allowed
        case PlaybackStateCompat.STATE_BUFFERING:
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
              FOREGROUND_NOTIFICATION_ID,
              getNotification(),
              playerAdapter.isRemote() ? ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE : ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
          } else {
            startForeground(FOREGROUND_NOTIFICATION_ID, getNotification());
          }
          break;
        case PlaybackStateCompat.STATE_PAUSED:
          releaseScheduler();
          isAllowedToRewind = false; // No relaunch on pause
          buildNotification();
          break;
        case PlaybackStateCompat.STATE_ERROR:
          releaseScheduler();
          // Try to relaunch just once
          if (isAllowedToRewind) {
            isAllowedToRewind = false;
            handler.postDelayed(() -> {
                try {
                  // Still in error?
                  if (mediaController.getPlaybackState().getState() == PlaybackStateCompat.STATE_ERROR) {
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
          playerAdapter.release();
          releaseScheduler();
          session.setMetadata(null);
          stopForeground(STOP_FOREGROUND_REMOVE);
          stopSelf();
          isAllowedToRewind = false;
      }
    });
  }

  @Override
  public int getPlaybackState() {
    return session.getController().getPlaybackState().getState();
  }

  @Override
  public void onTaskRemoved(@NonNull Intent rootIntent) {
    super.onTaskRemoved(rootIntent);
    stopSelf();
  }

  private void buildSessionMetadata(@NonNull Radio radio, @NonNull String information, @NonNull String playlist) {
    session.setMetadata(getMediaMetadataBuilder(radio, information).putString(PLAYLIST, playlist).build());
  }

  @NonNull
  private MediaMetadataCompat.Builder getMediaMetadataBuilder(@NonNull Radio radio, @NonNull String information) {
    return radio.getMediaMetadataBuilder(
      getString(R.string.app_name),
      playerAdapter.isRemote() ? " " + getString(R.string.remote) : "",
      information);
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
        .setSubText(playerAdapter.isRemote() ? getString(R.string.remote) : "");
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
    @Override
    public void onPlayFromMediaId(@NonNull String mediaId, @NonNull Bundle extras) {
      Log.d(LOG_TAG, "onPlayFromMediaId with mediaId: " + mediaId);
      // Ensure robustness
      if (upnpService != null) {
        upnpService.getActionController().release();
        // If from alarm, we must ensure UPnP is not used
        if (extras.getBoolean(getString(R.string.key_alarm_radio), false)) {
          upnpService.setSelectedDeviceIdentity(null);
        }
      }
      // Retrieve last radio
      final Radio lastRadio = playerAdapter.getRadio();
      // Stop player to be clean on resources (if not, audio focus is not well handled)
      playerAdapter.stop();
      // Stop scheduler if any
      releaseScheduler();
      // Try to retrieve radio
      final Radio radio = Radios.getInstance().getRadioFromId(mediaId);
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
      final boolean isLocal = (selectedDevice == null);
      playerAdapter.setSessionDevice(isLocal ?
        new LocalSessionDevice(
          RadioService.this,
          sessionDeviceListener,
          lockKey,
          radio,
          RadioHandler.getHandledUri(radioHttpServer.getLoopbackUri(), radio, lockKey)) :
        new UpnpSessionDevice(
          RadioService.this,
          sessionDeviceListener,
          lockKey,
          radio,
          RadioHandler.getHandledUri(serverUri, radio, lockKey),
          radioHttpServer.createLogoFile(radio),
          selectedDevice,
          upnpService.getActionController(),
          contentProvider));
      if (isLocal) {
        session.setPlaybackToLocal(AudioManager.STREAM_MUSIC);
      } else {
        session.setPlaybackToRemote(volumeProviderCompat);
      }
      // Synchronize session data
      session.setExtras(new Bundle());
      session.setPlaybackState(PlayerAdapter.getPlaybackStateCompatBuilder(PlaybackStateCompat.STATE_NONE).build());
      final MediaMetadataCompat mediaMetadataCompat = session.getController().getMetadata();
      final String lastPlaylist = (mediaMetadataCompat == null) ? "" : mediaMetadataCompat.getString(PLAYLIST);
      buildSessionMetadata(radio, "", (radio == lastRadio) ? lastPlaylist : "");
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
      onPlayFromMediaId(playerAdapter.getRadio());
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
      onPlay();
    }

    @Override
    public void onStop() {
      playerAdapter.stop();
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
        case ACTION_RELOAD:
          notifyChildrenChanged(MEDIA_ROOT_ID);
          break;
        default:
          Log.e(LOG_TAG, "onCustomAction: unknown command!");
      }
    }

    private void skipTo(int direction) {
      final Radio radio = playerAdapter.getRadio();
      if (radio == null) {
        // Should not happen
        Log.e(LOG_TAG, "skipTo: radio is null!");
      } else {
        final Radio nextRadio = Radios.getInstance().getRadioFrom(radio, direction);
        if (nextRadio == null) {
          Log.d(LOG_TAG, "skipTo: next radio is null!");
        } else {
          onPlayFromMediaId(Radios.getInstance().getRadioFrom(radio, direction));
        }
      }
    }

    // Same extras are reused
    private void onPlayFromMediaId(@Nullable Radio radio) {
      if (radio == null) {
        // Should not happen
        Log.e(LOG_TAG, "onPlayFromMediaId: radio is null!");
      } else {
        onPlayFromMediaId(radio.getId(), mediaController.getExtras());
      }
    }
  }
}