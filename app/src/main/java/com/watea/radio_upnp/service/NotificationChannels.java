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
import android.graphics.Color;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.DefaultMediaNotificationProvider;
import androidx.media3.session.MediaNotification;

import com.watea.radio_upnp.R;
import com.watea.radio_upnp.activity.MainActivity;

class NotificationChannels {
  static final int PLAYER_NOTIFICATION_ID = 9;
  private static final String LOG_TAG = NotificationChannels.class.getSimpleName();
  private static final int SLEEP_NOTIFICATION_ID = 42;
  private static final int REQUEST_CODE = 501;
  private final Context context;
  private final NotificationManagerCompat notificationManager;
  private final String channelId;
  private final PendingIntent contentIntent;
  @OptIn(markerClass = UnstableApi.class)
  private final MediaNotification.Provider mediaNotificationProvider;

  @OptIn(markerClass = UnstableApi.class)
  NotificationChannels(@NonNull Context context, @NonNull String tag) {
    this.context = context;
    notificationManager = NotificationManagerCompat.from(context);
    // Cancel own notifications to handle the case where the Service was killed and
    // restarted by the system; others belong to AlarmService
    notificationManager.cancel(PLAYER_NOTIFICATION_ID);
    notificationManager.cancel(SLEEP_NOTIFICATION_ID);
    // Create the (mandatory) silent notification channel; tag kept as is, so that channel id is unchanged
    channelId = create(
      context, tag, R.string.radio_service_notification_name, R.string.radio_service_description, NotificationManager.IMPORTANCE_LOW);
    // Built once: FLAG_CANCEL_CURRENT would cancel the instance held by the session
    contentIntent = PendingIntent.getActivity(
      context,
      REQUEST_CODE,
      new Intent(context, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
      PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    // Title: radio name (with remote suffix), text: stream information, see Radio.getMediaMetadataBuilder
    final DefaultMediaNotificationProvider provider = new DefaultMediaNotificationProvider.Builder(context)
      .setNotificationId(PLAYER_NOTIFICATION_ID)
      .setChannelId(channelId)
      .setChannelName(R.string.radio_service_notification_name)
      .build();
    provider.setSmallIcon(R.drawable.ic_mic_white_24dp);
    mediaNotificationProvider = provider;
  }

  // Returns the channel id.
  // Creating an existing channel is a no-op, except that its name and description are updated
  // and its importance is lowered, unless user has changed it.
  @NonNull
  static String create(@NonNull Context context, @NonNull String tag, int nameId, int descriptionId, int importance) {
    final String id = context.getString(R.string.app_name) + "." + tag;
    final NotificationChannel notificationChannel = new NotificationChannel(id, context.getString(nameId), importance);
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
          buildSleepCancelPendingIntent()))
        .build());
    } catch (SecurityException securityException) {
      Log.e(LOG_TAG, "Permission denied to post sleep timer notification", securityException);
    }
  }

  void cancelSleep() {
    notificationManager.cancel(SLEEP_NOTIFICATION_ID);
  }

  @NonNull
  private PendingIntent buildSleepCancelPendingIntent() {
    return PendingIntent.getForegroundService(
      context,
      REQUEST_CODE,
      new Intent(RadioService.ACTION_SLEEP_CANCEL, null, context, RadioService.class),
      PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }
}