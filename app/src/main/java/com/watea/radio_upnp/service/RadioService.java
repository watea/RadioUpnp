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
import androidx.annotation.OptIn;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.media.MediaBrowserServiceCompat;
import androidx.media.VolumeProviderCompat;
import androidx.media.session.MediaButtonReceiver;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.metadata.MetadataRenderer;

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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@OptIn(markerClass = UnstableApi.class)
public class RadioService
  extends MediaBrowserServiceCompat
  implements PlayerAdapter.StateController {
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
  private final Radios.Listener radiosListener = new Radios.Listener() {
    @Override
    public void onPreferredChange() {
      notifyChildrenChanged(MEDIA_ROOT_ID);
    }
  };
  private final AtomicReference<String> lockKey = new AtomicReference<>(getLockKey());
  private PlayerAdapter playerAdapter;
  private final VolumeProviderCompat volumeProviderCompat =
    new VolumeProviderCompat(VolumeProviderCompat.VOLUME_CONTROL_RELATIVE, 100, 50) {
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
  private UpnpStreamServer upnpStreamServer;
  private boolean isAllowedToRewind = false;
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
    @NonNull
    public String getLockKey() {
      return lockKey.get();
    }

    @Override
    public void onDisconnect(@NonNull String lockKey) {
      // Disconnect is not expected if playing
      final int state = getPlaybackState();
      if ((state == PlaybackStateCompat.STATE_BUFFERING) || (state == PlaybackStateCompat.STATE_PLAYING)) {
        onPlaybackStateChange(PlayerAdapter.getPlaybackStateCompatBuilder(PlaybackStateCompat.STATE_ERROR).build(), lockKey);
      }
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
      final int state = getPlaybackState();
      Log.d(LOG_TAG, "onCastStarted with state: " + state);
      if ((state == PlaybackStateCompat.STATE_BUFFERING) || (state == PlaybackStateCompat.STATE_PAUSED) || (state == PlaybackStateCompat.STATE_PLAYING)) {
        mediaSessionCompatCallback.onPlay();
      }
    }

    @Override
    public void onCastStop() {
      Log.d(LOG_TAG, "onCastStop");
      mediaSessionCompatCallback.onStop();
    }
  };
  private CastManager castManager;

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

  @NonNull
  private static String getLockKey() {
    return UUID.randomUUID().toString();
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
    // Player
    playerAdapter = new PlayerAdapter(this, this);
    // Launch HTTP server
    try {
      upnpStreamServer = new UpnpStreamServer(upnpStreamCallback);
      upnpStreamServer.start();
    } catch (IOException iOException) {
      Log.e(LOG_TAG, "HTTP server creation fails", iOException);
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
    // Stop player to be clean on resources (if not, audio focus is not well handled)
    playerAdapter.stop();
    // Release HTTP server
    if (upnpStreamServer != null) {
      upnpStreamServer.stop();
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
  public void onNewInformation(@NonNull String information, @NonNull String lockKey) {
    runIfLocked(lockKey, () -> {
      Log.d(LOG_TAG, "onNewInformation: " + information);
      final Radio radio = playerAdapter.getRadio();
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

  public void onNewBitrate(int bitrate, @NonNull String lockKey) {
    runIfLocked(lockKey, () -> {
      Log.d(LOG_TAG, "onNewBitrate: " + bitrate);
      // Rate in extras
      final Bundle extras = mediaController.getExtras();
      extras.putString(getString(R.string.key_rate), Integer.toString(bitrate));
      session.setExtras(extras);
      // Update notification
      buildNotification();
    });
  }

  // Only if lockKey still valid
  @SuppressLint("SwitchIntDef")
  @Override
  public void onPlaybackStateChange(@NonNull PlaybackStateCompat state, @NonNull String lockKey) {
    runIfLocked(lockKey, () -> {
      Log.d(LOG_TAG, "Valid state/lock key received: " + state.getState() + "/" + lockKey);
      // Report the state to the MediaSession
      session.setPlaybackState(state);
      // Manage the started state of this service, and session activity
      switch (state.getState()) {
        case PlaybackStateCompat.STATE_PLAYING:
          isAllowedToRewind = true;
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
      }
    });
  }

  @Override
  public boolean isPlaying() {
    return (getPlaybackState() == PlaybackStateCompat.STATE_PLAYING);
  }

  @Override
  public void onTaskRemoved(@NonNull Intent rootIntent) {
    super.onTaskRemoved(rootIntent);
    stopSelf();
  }

  private int getPlaybackState() {
    final PlaybackStateCompat playbackState = mediaController.getPlaybackState();
    return (playbackState == null) ? PlaybackStateCompat.STATE_ERROR : playbackState.getState();
  }

  private void buildSessionMetadata(@NonNull Radio radio, @NonNull String information, @NonNull String playlist) {
    session.setMetadata(radio.getMediaMetadataBuilder(
        getString(R.string.app_name),
        playerAdapter.isRemote() ? " " + getString(R.string.remote) : "",
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

  private void runIfLocked(@NonNull final String lockKey, @NonNull final Runnable runnable) {
    handler.post(() -> {
      if (session.isActive() && lockKey.equals(this.lockKey.get())) {
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
      // Try to retrieve radio
      final Radio radio = Radios.getInstance().getRadioFromId(mediaId);
      if (radio == null) {
        Log.e(LOG_TAG, "onPlayFromMediaId: radio not found");
        return;
      }
      Log.d(LOG_TAG, "onPlayFromMediaId with radio: " + radio.getName() + " => " + radio.getUri());
      // Retrieve last radio
      final Radio lastRadio = playerAdapter.getRadio();
      // Change session tag
      lockKey.set(getLockKey());
      // Clean current PlayerAdapter; must be done at each new lockKey
      playerAdapter.clean();
      // Stop scheduler if any
      releaseScheduler();
      // PlayerAdapter settings
      final SessionDevice sessionDevice = getSessionDevice(radio, lockKey.get());
      Log.d(LOG_TAG, "onPlayFromMediaId: sessionDevice => " + sessionDevice.getClass().getSimpleName());
      playerAdapter.setSessionDevice(sessionDevice);
      // Volume
      if (sessionDevice.isRemote()) {
        session.setPlaybackToRemote(volumeProviderCompat);
      } else {
        session.setPlaybackToLocal(AudioManager.STREAM_MUSIC);
      }
      // Synchronize session data
      session.setExtras(new Bundle());
      session.setPlaybackState(PlayerAdapter.getPlaybackStateCompatBuilder(PlaybackStateCompat.STATE_BUFFERING).build());
      final MediaMetadataCompat mediaMetadataCompat = mediaController.getMetadata();
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

    @NonNull
    private ExoPlayer getExoPlayer(@NonNull CapturingAudioSink capturingSink) {
      return new ExoPlayer.Builder(RadioService.this)
        .setRenderersFactory(
          (handler,
           videoListener,
           audioListener,
           textOutput,
           metadataOutput) -> new Renderer[]{
            new MediaCodecAudioRenderer(
              RadioService.this,
              MediaCodecSelector.DEFAULT,
              handler,
              audioListener,
              capturingSink),
            new MetadataRenderer(metadataOutput, handler.getLooper())
          })
        .build();
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
        Log.d(LOG_TAG, "onPlayFromMediaId: radio is null!");
      } else {
        onPlayFromMediaId(radio.getId(), mediaController.getExtras());
      }
    }

    // UPnP or Cast not accepted if environment not OK: force local processing
    @NonNull
    private SessionDevice getSessionDevice(@NonNull Radio radio, @NonNull String lockKey) {
      final String localIp = new NetworkProxy(RadioService.this).getWifiIpAddress();
      final Device upnpSelectedDevice = (upnpService == null) ? null : upnpService.getActiveSelectedDevice();
      final CapturingAudioSink capturingSink = new CapturingAudioSink(new DefaultAudioSink.Builder(RadioService.this).build(), lockKey);
      final ExoPlayer exoPlayer = getExoPlayer(capturingSink);
      final boolean isRemoteReady = (upnpStreamServer != null) && (localIp != null);
      if (isRemoteReady && castManager.hasCastSession()) {
        // Link capturingSink to upnpStreamServer
        capturingSink.setCallback(upnpStreamServer.getPcmCallback());
        return castManager.getCastSessionDevice(
          RadioService.this,
          exoPlayer,
          playerAdapter.getSessionDeviceListener(),
          lockKey,
          radio,
          upnpStreamServer.getStreamUri(localIp),
          upnpStreamServer.setLogo(radio, localIp));
      } else if (isRemoteReady && (upnpSelectedDevice != null)) {
        // Link capturingSink to upnpStreamServer
        capturingSink.setCallback(upnpStreamServer.getPcmCallback());
        return new UpnpSessionDevice(
          RadioService.this,
          exoPlayer,
          playerAdapter.getSessionDeviceListener(),
          lockKey,
          radio,
          upnpStreamServer.getStreamUri(localIp),
          upnpStreamServer.setLogo(radio, localIp),
          upnpSelectedDevice,
          upnpService.getActionController(),
          upnpStreamServer::stopStream);
      } else {
        return new LocalSessionDevice(
          RadioService.this,
          exoPlayer,
          playerAdapter.getSessionDeviceListener(),
          lockKey,
          radio);
      }
    }
  }
}