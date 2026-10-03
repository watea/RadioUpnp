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
import android.content.Context;
import android.graphics.Color;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationManagerCompat;

import com.watea.radio_upnp.R;

// Notification channel setup shared by the foreground services
public class NotificationChannels {
  // Returns the channel id.
  // Creating an existing channel is a no-op, except that its name and description are updated.
  @NonNull
  public static String create(@NonNull Context context, @NonNull String tag, int nameId, int descriptionId) {
    final String id = context.getString(R.string.app_name) + "." + tag;
    final NotificationChannel notificationChannel =
      new NotificationChannel(id, context.getString(nameId), NotificationManager.IMPORTANCE_HIGH);
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
}