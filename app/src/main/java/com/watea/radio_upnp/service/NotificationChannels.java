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

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.media3.common.DeviceInfo;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.CommandButton;
import androidx.media3.session.MediaNotification;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaStyleNotificationHelper.MediaStyle;

import com.google.common.collect.ImmutableList;
import com.watea.radio_upnp.R;
import com.watea.radio_upnp.activity.MainActivity;

class NotificationChannels {
  static final int PLAYER_NOTIFICATION_ID = 9;
  // Media action intent strings (notification PendingIntents → RadioService.onStartCommand)
  static final String ACTION_MEDIA_PLAY = "ACTION_MEDIA_PLAY";
  static final String ACTION_MEDIA_PAUSE = "ACTION_MEDIA_PAUSE";
  static final String ACTION_MEDIA_STOP = "ACTION_MEDIA_STOP";
  static final String ACTION_MEDIA_SKIP_NEXT = "ACTION_MEDIA_SKIP_NEXT";
  static final String ACTION_MEDIA_SKIP_PREVIOUS = "ACTION_MEDIA_SKIP_PREVIOUS";
  private static final String LOG_TAG = NotificationChannels.class.getSimpleName();
  private static final int SLEEP_NOTIFICATION_ID = 42;
  private static final int REQUEST_CODE = 501;
  private final Context context;
  private final RadioPlayer radioPlayer;
  private final NotificationManagerCompat notificationManager;
  private final String channelId;
  private final PendingIntent contentIntent;
  private final NotificationCompat.Action actionPause;
  private final NotificationCompat.Action actionPlay;
  private final NotificationCompat.Action actionSkipToNext;
  private final NotificationCompat.Action actionSkipToPrevious;
  // Only the channel is used, for Media3 own notifications; RadioService builds the player notification
  @OptIn(markerClass = UnstableApi.class)
  private final MediaNotification.Provider mediaNotificationProvider = new MediaNotification.Provider() {
    @NonNull
    @Override
    public MediaNotification createNotification(
      @NonNull MediaSession mediaSession,
      @NonNull ImmutableList<CommandButton> mediaButtonPreferences,
      @NonNull MediaNotification.ActionFactory actionFactory,
      @NonNull Callback onNotificationChangedCallback) {
      return new MediaNotification(PLAYER_NOTIFICATION_ID, getPlayerNotification(mediaSession));
    }

    @Override
    public boolean handleCustomCommand(@NonNull MediaSession session, @NonNull String action, @NonNull Bundle extras) {
      return false;
    }

    @NonNull
    @Override
    public NotificationChannelInfo getNotificationChannelInfo() {
      return new NotificationChannelInfo(channelId, context.getString(R.string.radio_service_notification_name));
    }
  };

  NotificationChannels(@NonNull Context context, @NonNull String tag, @NonNull RadioPlayer radioPlayer) {
    this.context = context;
    this.radioPlayer = radioPlayer;
    notificationManager = NotificationManagerCompat.from(context);
    // Cancel own notifications to handle the case where the Service was killed and
    // restarted by the system; others belong to AlarmService
    notificationManager.cancel(PLAYER_NOTIFICATION_ID);
    notificationManager.cancel(SLEEP_NOTIFICATION_ID);
    // Create the (mandatory) notification channel; tag kept as is, so that channel id is unchanged
    channelId = create(context, tag, R.string.radio_service_notification_name, R.string.radio_service_description);
    // Built once: FLAG_CANCEL_CURRENT would cancel the instance held by the session
    contentIntent = PendingIntent.getActivity(
      context,
      REQUEST_CODE,
      new Intent(context, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
      PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    actionPause = buildServiceAction(R.drawable.ic_pause_white_24dp, R.string.action_pause, ACTION_MEDIA_PAUSE);
    actionPlay = buildServiceAction(R.drawable.ic_play_arrow_white_24dp, R.string.action_play, ACTION_MEDIA_PLAY);
    actionSkipToNext = buildServiceAction(R.drawable.ic_skip_next_white_24dp, R.string.action_skip_to_next, ACTION_MEDIA_SKIP_NEXT);
    actionSkipToPrevious = buildServiceAction(R.drawable.ic_skip_previous_white_24dp, R.string.action_skip_to_previous, ACTION_MEDIA_SKIP_PREVIOUS);
  }

  // Returns the channel id.
  // Creating an existing channel is a no-op, except that its name and description are updated.
  @NonNull
  static String create(@NonNull Context context, @NonNull String tag, int nameId, int descriptionId) {
    final String id = context.getString(R.string.app_name) + "." + tag;
    final NotificationChannel notificationChannel = new NotificationChannel(id, context.getString(nameId), NotificationManager.IMPORTANCE_HIGH);
    notificationChannel.setDescription(context.getString(descriptionId)); // User-visible
    notificationChannel.enableLights(true);
    notificationChannel.enableVibration(false);
    // Sets the notification light color for notifications posted to this
    // channel, if the device supports this feature
    notificationChannel.setLightColor(Color.GREEN);
    notificationChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
    NotificationManagerCompat.from(context).createNotificationChannel(notificationChannel);
    return id;
  }

  @NonNull
  PendingIntent getContentIntent() {
    return contentIntent;
  }

  @OptIn(markerClass = UnstableApi.class)
  @NonNull
  MediaNotification.Provider getMediaNotificationProvider() {
    return mediaNotificationProvider;
  }

  @OptIn(markerClass = UnstableApi.class)
  @NonNull
  Notification getPlayerNotification(@NonNull MediaSession session) {
    final MediaStyle mediaStyle = new MediaStyle(session);
    final NotificationCompat.Builder builder = new NotificationCompat.Builder(context, channelId)
      .setSmallIcon(R.drawable.ic_mic_white_24dp)
      // Pending intent that is fired when user clicks on notification
      .setContentIntent(contentIntent)
      // When notification is deleted (when playback is paused and notification can be
      // deleted), fire intent with ACTION_STOP
      .setDeleteIntent(buildServicePendingIntent(ACTION_MEDIA_STOP))
      // Show controls on lock screen even when user hides sensitive content
      .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
      .setSilent(true);
    final MediaItem currentItem = radioPlayer.getCurrentMediaItem();
    final MediaMetadata metadata = (currentItem != null) ? currentItem.mediaMetadata : null;
    if (metadata != null) {
      if (metadata.artworkData != null) {
        builder.setLargeIcon(BitmapFactory.decodeByteArray(
          metadata.artworkData, 0, metadata.artworkData.length));
      }
      final boolean isRemote = (radioPlayer.getDeviceInfo().playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE);
      builder
        .setContentTitle(metadata.subtitle)
        .setContentText(metadata.displayTitle)
        .setSubText(isRemote ? context.getString(R.string.remote) : "");
    }
    builder.addAction(actionSkipToPrevious);
    switch (radioPlayer.getSessionDeviceState()) {
      case PLAYING:
        builder
          .addAction(actionPause)
          .setOngoing(true);
        mediaStyle.setShowActionsInCompactView(0, 1, 2);
        break;
      case PAUSED:
      case ERROR:
      case UNSUPPORTED:
        builder
          .addAction(actionPlay)
          .setOngoing(false);
        mediaStyle.setShowActionsInCompactView(0, 1, 2);
        break;
      default:
        builder.setOngoing(false);
        mediaStyle.setShowActionsInCompactView(0, 1);
    }
    builder.addAction(actionSkipToNext);
    return builder.setStyle(mediaStyle).build();
  }

  void notifyPlayer(@NonNull MediaSession session) {
    try {
      notificationManager.notify(PLAYER_NOTIFICATION_ID, getPlayerNotification(session));
    } catch (SecurityException securityException) {
      Log.e(LOG_TAG, "Internal failure; notification not allowed");
    }
  }

  void notifySleep(int minutes) {
    try {
      notificationManager.notify(SLEEP_NOTIFICATION_ID, new NotificationCompat.Builder(context, channelId)
        .setSmallIcon(R.drawable.ic_mic_white_24dp)
        .setContentTitle(context.getString(R.string.sleep_timer_title))
        .setContentText(context.getString(R.string.sleep_timer_set_for, minutes))
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .addAction(new NotificationCompat.Action(
          R.drawable.ic_stop_white_24dp,
          context.getString(R.string.cancel_sleep_timer),
          buildServicePendingIntent(RadioService.ACTION_SLEEP_CANCEL)))
        .build());
    } catch (SecurityException securityException) {
      Log.e(LOG_TAG, "Permission denied to post sleep timer notification", securityException);
    }
  }

  void cancelSleep() {
    notificationManager.cancel(SLEEP_NOTIFICATION_ID);
  }

  @NonNull
  private PendingIntent buildServicePendingIntent(@NonNull String action) {
    return PendingIntent.getForegroundService(
      context,
      REQUEST_CODE,
      new Intent(action, null, context, RadioService.class),
      PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  @NonNull
  private NotificationCompat.Action buildServiceAction(int iconId, int titleId, @NonNull String action) {
    return new NotificationCompat.Action(iconId, context.getString(titleId), buildServicePendingIntent(action));
  }
}