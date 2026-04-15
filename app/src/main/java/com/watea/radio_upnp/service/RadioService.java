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
import com.watea.radio_upnp.cast.CastManager;
import com.watea.radio_upnp.model.LocalSessionDevice;
import com.watea.radio_upnp.model.Radio;
import com.watea.radio_upnp.model.Radios;
import com.watea.radio_upnp.model.SessionDevice;
import com.watea.radio_upnp.model.UpnpSessionDevice;
import com.watea.radio_upnp.upnp.Device;

import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
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
  implements SessionDevice.Listener {
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
  private final MediaSessionCompatCallback mediaSessionCompatCallback = new MediaSessionCompatCallback();
  @Nullable
  private Result<List<MediaBrowserCompat.MediaItem>> loadResult = null;
  private boolean isLastRadioToLaunch = false;
  private volatile boolean isAllowedToRewind = false;
  @NonNull
  private volatile String lockKey = getLockKey();
  @Nullable
  private PlayerAdapter playerAdapter = null;
  private final VolumeProviderCompat volumeProviderCompat = new VolumeProviderCompat(VolumeProviderCompat.VOLUME_CONTROL_RELATIVE, 100, 50) {
    @Override
    public void onAdjustVolume(int direction) {
      playerAdapter.adjustVolume(direction);
    }
  };
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
  @Nullable
  private UpnpStreamServer upnpStreamServer = null;
  @Nullable
  private ScheduledExecutorService scheduler = null;
  private NotificationCompat.Action actionPause;
  private NotificationCompat.Action actionPlay;
  private NotificationCompat.Action actionRewind;
  private NotificationCompat.Action actionSkipToNext;
  private NotificationCompat.Action actionSkipToPrevious;
  private MediaControllerCompat mediaController;
  private final UpnpStreamServer.Callback upnpStreamCallback = new UpnpStreamServer.Callback() {
    @Override
    public void onConnected(@NonNull String lockKey) {
      Log.d(LOG_TAG, "onConnected: " + lockKey);
      if (lockKey.equals(RadioService.this.lockKey)) {
        isAllowedToRewind = true;
      }
    }

    @Override
    public void onInformation(@NonNull String information, @NonNull String lockKey) {
      runIfLocked(lockKey, () -> onNewInformation(information, lockKey));
    }

    @Override
    public void onDisconnected(@NonNull String lockKey) {
      Log.d(LOG_TAG, "onDisconnected: " + lockKey);
      runIfLocked(lockKey, () -> onPlaybackStateChange(SessionDevice.getPlaybackStateCompatBuilder(PlaybackStateCompat.STATE_ERROR).build()));
    }
  };
  private CastManager castManager;
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
      final int state = getPlaybackState();
      Log.d(LOG_TAG, "onCastStarted with state: " + state);
      if ((state == PlaybackStateCompat.STATE_BUFFERING) || (state == PlaybackStateCompat.STATE_PAUSED) || (state == PlaybackStateCompat.STATE_PLAYING)) {
        // We relaunch a session
        mediaSessionCompatCallback.onRewind();
      }
    }

    @Override
    public void onCastStop() {
      Log.d(LOG_TAG, "onCastStop");
      mediaSessionCompatCallback.onStop();
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
    public void onInitEnd() {
      Log.d(LOG_TAG, "onInitEnd");
      handler.post(() -> {
        if (loadResult != null) {
          onLoadChildren(loadResult);
          loadResult = null;
        }
        if (isLastRadioToLaunch) {
          mediaSessionCompatCallback.launchLastRadio();
          isLastRadioToLaunch = false;
        }
      });
    }

    private void notifyChildrenChanged() {
      if (Radios.isInit()) {
        RadioService.this.notifyChildrenChanged(MEDIA_ROOT_ID);
      }
    }
  };

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
  public static String getLockKey() {
    return UUID.randomUUID().toString();
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
    // Cast
    castManager = CastManager.getInstance();
    castManager.setContext(this, castManagerCallback);
    // Create radios if needed
    Radios.setInstance(this, null);
    Radios.getInstance().addListener(radiosListener);
    // Launch HTTP server
    try {
      upnpStreamServer = new UpnpStreamServer(this, upnpStreamCallback);
      upnpStreamServer.start();
    } catch (IOException iOException) {
      Log.e(LOG_TAG, "HTTP server creation failed", iOException);
      upnpStreamServer = null;
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
    // Radios
    Radios.getInstance().removeListener(radiosListener);
    // Stop player to be clean on resources (if not, audio focus is not well handled)
    if (playerAdapter != null) {
      playerAdapter.stop();
    }
    // Release HTTP server
    if (upnpStreamServer != null) {
      try {
        upnpStreamServer.stop();
      } catch (IOException ioException) {
        Log.d(LOG_TAG, "onDestroy: unable to stop HTTP server", ioException);
      }
    }
    // Release UPnP service
    unbindService(upnpConnection);
    // Release Cast
    castManager.resetContext(this);
    // Finally session
    session.setActive(false);
    session.release();
  }

  @NonNull
  @Override
  public BrowserRoot onGetRoot(@NonNull String clientPackageName, int clientUid, @Nullable Bundle rootHints) {
    Log.d(LOG_TAG, "onGetRoot: with clientPackageName = " + clientPackageName + ", rootHints = " + (rootHints == null ? "null" : rootHints.toString()));
    return new BrowserRoot(MEDIA_ROOT_ID, null);
  }

  @Override
  public void onLoadChildren(@NonNull final String parentMediaId, @NonNull final Result<List<MediaBrowserCompat.MediaItem>> result) {
    Log.d(LOG_TAG, "onLoadChildren: with parentMediaId = " + parentMediaId);
    if (MEDIA_ROOT_ID.equals(parentMediaId)) {
      if (Radios.isInit()) {
        onLoadChildren(result);
      } else {
        Log.d(LOG_TAG, "onLoadChildren: detach");
        result.detach();
        this.loadResult = result;
      }
    } else {
      result.sendResult(Collections.emptyList());
    }
  }

  @Override
  public void onNewInformation(@NonNull String information, @NonNull String lockKey) {
    runIfLocked(lockKey, () -> {
      Log.d(LOG_TAG, "onNewInformation: " + information);
      final Radio radio = (playerAdapter == null) ? null : playerAdapter.getRadio();
      if (radio != null) {
        final MediaMetadataCompat mediaMetadataCompat = mediaController.getMetadata();
        if (mediaMetadataCompat != null) {
          buildSessionMetadata(radio, information, addPlaylistItem(mediaMetadataCompat.getString(PLAYLIST), information));
          // Update notification
          buildNotification();
        }
      }
    });
  }

  @Override
  public void onNewBitrate(int bitrate, @NonNull String mimeType, @NonNull String lockKey) {
    runIfLocked(lockKey, () -> {
      Log.d(LOG_TAG, "onNewBitrate: " + bitrate);
      // Rate in extras
      final Bundle extras = mediaController.getExtras();
      final String bitrateDisplay = (bitrate > 0) ? Integer.toString(bitrate) : "--";
      extras.putString(getString(R.string.key_bitrate), bitrateDisplay);
      extras.putString(getString(R.string.key_mime_type), mimeType);
      session.setExtras(extras);
      // Update notification
      buildNotification();
    });
  }

  // Only if lockKey still valid
  @SuppressLint("SwitchIntDef")
  @Override
  public void onPlaybackStateChange(@NonNull PlaybackStateCompat state, @NonNull String lockKey) {
    runIfLocked(lockKey, () -> onPlaybackStateChange(state));
  }

  @Override
  public void onTaskRemoved(@NonNull Intent rootIntent) {
    super.onTaskRemoved(rootIntent);
    stopSelf();
  }

  @Override
  public int getPlaybackState() {
    final PlaybackStateCompat playbackState = mediaController.getPlaybackState();
    return (playbackState == null) ? PlaybackStateCompat.STATE_ERROR : playbackState.getState();
  }

  private void onLoadChildren(@NonNull final Result<List<MediaBrowserCompat.MediaItem>> result) {
    Log.d(LOG_TAG, "onLoadChildren");
    final List<MediaBrowserCompat.MediaItem> mediaItems = new ArrayList<>();
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
    result.sendResult(mediaItems);
  }

  private void onPlaybackStateChange(@NonNull PlaybackStateCompat state) {
    Log.d(LOG_TAG, "onPlaybackStateChange: " + SessionDevice.getStateName(state.getState()) + " - " + lockKey);
    final int currentState = getPlaybackState();
    // Nothing can change if Stopped
    if (currentState == PlaybackStateCompat.STATE_STOPPED) {
      return;
    }
    final int intState = state.getState();
    // We do nothing if nothing change
    if (intState == currentState) {
      return;
    }
    // Error is not accepted if remote and paused
    if ((playerAdapter == null) || playerAdapter.isRemote() && (intState == PlaybackStateCompat.STATE_ERROR) && (currentState == PlaybackStateCompat.STATE_PAUSED)) {
      return;
    }
    // Report the state to the MediaSession
    session.setPlaybackState(state);
    // Manage the started state of this service, and session activity
    switch (intState) {
      case PlaybackStateCompat.STATE_PLAYING:
        if (!playerAdapter.isRemote()) {
          isAllowedToRewind = true;
        }
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
      case PlaybackStateCompat.STATE_ERROR:
        playerAdapter.release();
        if (isAllowedToRewind) {
          isAllowedToRewind = false;
          mediaSessionCompatCallback.onRewind();
          break;
        }
      case PlaybackStateCompat.STATE_PAUSED:
        assert upnpStreamServer != null;
        upnpStreamServer.release();
        releaseScheduler();
        buildNotification();
        break;
      default:
        // Release everything
        assert upnpStreamServer != null;
        upnpStreamServer.release();
        playerAdapter.release();
        releaseScheduler();
        session.setMetadata(null);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }
  }

  private void buildSessionMetadata(@NonNull Radio radio, @NonNull String information, @NonNull String playlist) {
    session.setMetadata(radio.getMediaMetadataBuilder(
        getString(R.string.app_name),
        (playerAdapter != null) && playerAdapter.isRemote() ? " " + getString(R.string.remote) : "",
        information)
      .putString(PLAYLIST, playlist).build());
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
      Log.d(LOG_TAG, "getNotification: no metadata defined for radio");
    } else {
      final MediaDescriptionCompat description = mediaMetadataCompat.getDescription();
      builder
        .setLargeIcon(description.getIconBitmap())
        // Title, radio name
        .setContentTitle(description.getTitle())
        // Radio current track
        .setContentText(description.getSubtitle())
        // Remote?
        .setSubText((playerAdapter != null) && playerAdapter.isRemote() ? getString(R.string.remote) : "");
    }
    final androidx.media.app.NotificationCompat.MediaStyle mediaStyle =
      new androidx.media.app.NotificationCompat.MediaStyle().setMediaSession(getSessionToken());
    builder.addAction(actionSkipToPrevious);
    switch (getPlaybackState()) {
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

  private void runIfLocked(@NonNull final String lockKey, @NonNull final Runnable runnable) {
    handler.post(() -> {
      if (session.isActive() && lockKey.equals(this.lockKey)) {
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
      // Avoid internal failure
      if (upnpStreamServer == null) {
        Log.e(LOG_TAG, "onPlayFromMediaId: upnpStreamServer is null");
        return;
      }
      // Try to retrieve radio
      final Radio radio = Radios.getInstance().getRadioFromId(mediaId);
      if (radio == null) {
        Log.e(LOG_TAG, "onPlayFromMediaId: radio not found");
        return;
      }
      Log.d(LOG_TAG, "onPlayFromMediaId with radio: " + radio.getName() + " => " + radio.getUri());
      // Store
      MainActivity.getAppPreferences(RadioService.this).edit().putString(getString(R.string.key_last_played_radio), radio.getId()).apply();
      // Retrieve last radio
      final Radio lastRadio = (playerAdapter == null) ? null : playerAdapter.getRadio();
      // Change session tag
      lockKey = getLockKey();
      // Clean current PlayerAdapter; must be done at each new lockKey
      playerAdapter.clean();
      // Stop scheduler if any
      releaseScheduler();
      // PlayerAdapter settings
      final SessionDevice sessionDevice = getSessionDevice(radio, lockKey);
      playerAdapter = new PlayerAdapter(RadioService.this, sessionDevice);
      // Volume
      if (sessionDevice.isRemote()) {
        session.setPlaybackToRemote(volumeProviderCompat);
      } else {
        session.setPlaybackToLocal(AudioManager.STREAM_MUSIC);
      }
      // Synchronize session data
      session.setExtras(new Bundle());
      session.setPlaybackState(SessionDevice.getPlaybackStateCompatBuilder(PlaybackStateCompat.STATE_BUFFERING).build());
      final MediaMetadataCompat mediaMetadataCompat = mediaController.getMetadata();
      final String lastPlaylist = (mediaMetadataCompat == null) ? "" : mediaMetadataCompat.getString(PLAYLIST);
      buildSessionMetadata(radio, "", (radio == lastRadio) ? lastPlaylist : "");
      // Start service, must be done while activity has foreground
      isAllowedToRewind = false;
      if (playerAdapter.isRemote()) {
        upnpStreamServer.launch(lockKey);
      }
      if (playerAdapter.prepare()) {
        startForegroundService(new Intent(RadioService.this, RadioService.class));
      } else {
        playerAdapter.stop();
        Log.d(LOG_TAG, "onPlayFromMediaId: playerAdapter failed");
      }
    }

    @Override
    public void onPlayFromSearch(String query, Bundle extras) {
      Log.d(LOG_TAG, "onPlayFromSearch: query = " + query);
      final Radios radios = Radios.getInstance();
      Radio match = null;
      if ((query == null) || query.trim().isEmpty()) {
        // No query — play the first available radio
        if (!radios.isEmpty()) {
          match = radios.get(0);
        }
      } else {
        final String normalizedQuery = query.trim().toLowerCase(Locale.getDefault());
        // 1. Exact name match
        match = radios.stream().filter(radio -> radio.getName().toLowerCase(Locale.getDefault()).equals(normalizedQuery)).findAny().orElse(null);
        // 2. Partial name match
        if (match == null) {
          match = radios.stream().filter(radio -> radio.getName().toLowerCase(Locale.getDefault()).contains(normalizedQuery)).findAny().orElse(null);
        }
      }
      if (match == null) {
        Log.w(LOG_TAG, "onPlayFromSearch: no match found for query = " + query);
      } else {
        Log.d(LOG_TAG, "onPlayFromSearch: matched radio = " + match.getName());
        onPlay(match);
      }
    }

    @Override
    public void onPlay() {
      Log.d(LOG_TAG, "onPlay");
      // Is it an init call?
      if (playerAdapter == null) {
        isLastRadioToLaunch = !launchLastRadio();
      } else {
        playerAdapter.play();
      }
    }

    @Override
    public void onPause() {
      Log.d(LOG_TAG, "onPause");
      if (playerAdapter == null) {
        Log.e(LOG_TAG, "onPause: playerAdapter is null!");
        return;
      }
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
      Log.d(LOG_TAG, "onRewind");
      if (playerAdapter == null) {
        Log.e(LOG_TAG, "onRewind: playerAdapter is null!");
        return;
      }
      // We relaunch a session
      final Radio radio = playerAdapter.getRadio();
      if (radio != null) {
        onPlayFromMediaId(radio.getId(), new Bundle());
      }
    }

    @Override
    public void onStop() {
      Log.d(LOG_TAG, "onStop");
      if (playerAdapter == null) {
        Log.e(LOG_TAG, "onStop: playerAdapter is null!");
        return;
      }
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

    public void onPlay(@Nullable Radio radio) {
      if (radio == null) {
        Log.d(LOG_TAG, "onPlay: radio is null!");
      } else {
        onPlayFromMediaId(radio.getId(), new Bundle());
      }
    }

    // true if work done
    private boolean launchLastRadio() {
      Log.d(LOG_TAG, "launchLastRadio");
      if (Radios.isInit()) {
        final Radios radios = Radios.getInstance();
        if (!radios.isEmpty()) {
          onPlayFromMediaId(
            MainActivity.getAppPreferences(RadioService.this).getString(getString(R.string.key_last_played_radio), radios.get(0).getId()),
            new Bundle());
        }
        return true;
      }
      return false;
    }

    private void skipTo(int direction) {
      if (playerAdapter == null) {
        Log.e(LOG_TAG, "skipTo: playerAdapter is null!");
        return;
      }
      final Radio radio = playerAdapter.getRadio();
      if (radio == null) {
        // Should not happen
        Log.e(LOG_TAG, "skipTo: radio is null!");
      } else {
        final Radio nextRadio = Radios.getInstance().getRadioFrom(radio, direction);
        if (nextRadio == null) {
          Log.d(LOG_TAG, "skipTo: next radio is null!");
        } else {
          onPlay(Radios.getInstance().getRadioFrom(radio, direction));
        }
      }
    }

    // UPnP or Cast not accepted if environment not OK: force local processing.
    // Cat always in PCM.
    // upnpStreamServer shall be not null.
    @NonNull
    private SessionDevice getSessionDevice(@NonNull Radio radio, @NonNull String lockKey) {
      assert upnpStreamServer != null;
      final Device upnpSelectedDevice = (upnpService == null) ? null : upnpService.getActiveSelectedDevice();
      final boolean isRemoteReady = new NetworkProxy(RadioService.this).isOnWifi();
      final SessionDevice.Mode mode = MainActivity.getAppPreferences(RadioService.this).getBoolean(RadioService.this.getString(R.string.key_pcm_mode), true) ?
        SessionDevice.Mode.PCM : SessionDevice.Mode.MUTE;
      final SessionDevice result = (isRemoteReady && castManager.hasCastSession()) ?
        castManager.getCastSessionDevice(
          RadioService.this,
          upnpStreamServer,
          RadioService.this,
          radio,
          lockKey) :
        (isRemoteReady && (upnpSelectedDevice != null)) ?
          new UpnpSessionDevice(
            RadioService.this,
            mode,
            upnpStreamServer,
            RadioService.this,
            radio,
            lockKey,
            upnpSelectedDevice,
            upnpService.getActionController(),
            mediaSessionCompatCallback::onPlay) :
          new LocalSessionDevice(
            RadioService.this,
            RadioService.this,
            radio,
            lockKey);
      Log.d(LOG_TAG, "getSessionDevice: " + result.getClass().getSimpleName() + " - " + lockKey);
      return result;
    }
  }
}