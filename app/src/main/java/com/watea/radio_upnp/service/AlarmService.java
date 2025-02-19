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

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.watea.radio_upnp.R;
import com.watea.radio_upnp.activity.MainActivity;
import com.watea.radio_upnp.model.Radio;

import java.util.Calendar;
import java.util.Locale;

public class AlarmService extends Service {
  private static final String LOG_TAG = AlarmService.class.getSimpleName();
  private static final int NOTIFICATION_ID = 10;
  private static final int DEFAULT_TIME = -1;
  private final Binder binder = new AlarmServiceBinder();
  // Callback from media control
  private final MediaControllerCompat.Callback mediaControllerCallback = new MediaControllerCompatCallback();
  // MediaController from the MediaBrowser when it has successfully connected
  private final MediaBrowserCompat.ConnectionCallback mediaBrowserConnectionCallback = new MediaBrowserCompatConnectionCallback();
  private String CHANNEL_ID;
  private MediaBrowserCompat mediaBrowser = null;
  // MediaController from the MediaBrowser when it has successfully connected
  @Nullable
  private MediaControllerCompat mediaController = null;
  private AlarmManager alarmManager;
  private boolean isStarted = false;

  @Override
  public void onCreate() {
    super.onCreate();
    Log.d(LOG_TAG, "onCreate");
    // Notification
    CHANNEL_ID = getResources().getString(R.string.app_name) + "." + LOG_TAG;
    final NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this);
    // Cancel all notifications to handle the case where the Service was killed and
    // restarted by the system
    notificationManager.cancelAll();
    // Create the (mandatory) notification channel
    if (notificationManager.getNotificationChannel(CHANNEL_ID) == null) {
      final NotificationChannel notificationChannel = new NotificationChannel(
        CHANNEL_ID,
        getString(R.string.alarm_service_notification_name),
        NotificationManager.IMPORTANCE_HIGH);
      // Configure the notification channel
      notificationChannel.setDescription(getString(R.string.alarm_service_description)); // User-visible
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
    // Create MediaBrowserServiceCompat
    mediaBrowser = new MediaBrowserCompat(
      this,
      new ComponentName(this, RadioService.class),
      mediaBrowserConnectionCallback,
      null);
    // AlarmManager
    alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    Log.d(LOG_TAG, "onDestroy");
    // Robustness
    releaseAlarm();
    releaseMediaBrowser();
    isStarted = false;
  }

  @Nullable
  @Override
  public IBinder onBind(Intent intent) {
    return binder;
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    Log.d(LOG_TAG, "onStartCommand");
    if (intent.getBooleanExtra(getString(R.string.key_alarm), false)) {
      // Launch RadioService, may fail if already called and connection not ended
      try {
        mediaBrowser.connect();
      } catch (IllegalStateException illegalStateException) {
        Log.e(LOG_TAG, "onStartCommand: mediaBrowser.connect() failed", illegalStateException);
      }
      // Relaunch alarm
      final AlarmServiceBinder alarmServiceBinder = (AlarmServiceBinder) binder;
      if (!setAlarmManagerAlarm(alarmServiceBinder.getHour(), alarmServiceBinder.getMinute())) {
        alarmServiceBinder.cancelAlarm();
      }
      return super.onStartCommand(intent, flags, startId);
    } else {
      if (!isStarted) {
        startForeground(NOTIFICATION_ID, getNotification());
        isStarted = true;
      }
      return START_STICKY;
    }
  }

  private boolean setAlarmManagerAlarm(int hour, int minute) {
    if ((Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) && !alarmManager.canScheduleExactAlarms()) {
      return false;
    }
    final Calendar calendar = Calendar.getInstance();
    calendar.set(Calendar.HOUR_OF_DAY, hour);
    calendar.set(Calendar.MINUTE, minute);
    calendar.set(Calendar.SECOND, 0);
    // If the time is in the past, add one day
    if (calendar.getTimeInMillis() < System.currentTimeMillis()) {
      calendar.add(Calendar.DAY_OF_YEAR, 1);
    }
    // Set
    final PendingIntent pendingIntent = getPendingIntent();
    final AlarmManager.AlarmClockInfo alarmClockInfo = new AlarmManager.AlarmClockInfo(
      calendar.getTimeInMillis(),
      pendingIntent);
    try {
      alarmManager.setAlarmClock(alarmClockInfo, pendingIntent);
    } catch (SecurityException securityException) {
      Log.e(LOG_TAG, "setAlarmManagerAlarm: internal failure", securityException);
      return false;
    }
    return true;
  }

  @NonNull
  private Notification getNotification() {
    final AlarmServiceBinder alarmServiceBinder = (AlarmServiceBinder) binder;
    final String radioName = (alarmServiceBinder.getRadio() == null) ? getString(R.string.no_radio_available) : alarmServiceBinder.getRadio().getName();
    return new NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_baseline_mic_white_24dp)
      .setContentTitle(getString(R.string.alarm_set) + alarmServiceBinder.getHour() + "." + String.format(Locale.getDefault(), "%02d", alarmServiceBinder.getMinute()) + " / " + radioName)
      .build();
  }

  private boolean radioLaunch() {
    final Radio radio = ((AlarmServiceBinder) binder).getRadio();
    if (radio == null) {
      Log.e(LOG_TAG, "radioLaunch: alarm radio is null!");
    } else {
      Log.d(LOG_TAG, "radioLaunch: alarm on radio => " + radio.getName());
      if (mediaController == null) {
        Log.e(LOG_TAG, "radioLaunch: mediaController is null!");
      } else {
        mediaController.getTransportControls().prepareFromMediaId(radio.getId(), new Bundle());
        return true;
      }
    }
    return false;
  }

  private void releaseMediaBrowser() {
    mediaBrowser.disconnect();
    // Robustness: force suspended connection
    mediaBrowserConnectionCallback.onConnectionSuspended();
  }

  private void releaseAlarm() {
    alarmManager.cancel(getPendingIntent());
  }

  @NonNull
  private PendingIntent getPendingIntent() {
    return PendingIntent.getBroadcast(
      AlarmService.this,
      0,
      new Intent(AlarmService.this, AlarmReceiver.class),
      PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  public static class AlarmReceiver extends BroadcastReceiver {
    private static final String LOG_TAG = AlarmReceiver.class.getSimpleName();

    @Override
    public void onReceive(Context context, Intent intent) {
      Log.d(LOG_TAG, "onReceive");
      final Intent serviceIntent = new Intent(context, AlarmService.class);
      serviceIntent.putExtra(context.getString(R.string.key_alarm), true);
      context.startForegroundService(serviceIntent);
    }
  }

  public class AlarmServiceBinder extends android.os.Binder {
    public boolean setAlarm(int hour, int minute, @NonNull String radioURL) {
      getSharedPreferences()
        .edit()
        .putString(getString(R.string.key_alarm_radio), radioURL)
        .putInt(getString(R.string.key_alarm_hour), hour)
        .putInt(getString(R.string.key_alarm_minute), minute)
        .apply();
      releaseAlarm(); // Robustness
      if (setAlarmManagerAlarm(hour, minute)) {
        startForegroundService(new Intent(AlarmService.this, AlarmService.class));
        return true;
      }
      return false;
    }

    public void cancelAlarm() {
      if (isStarted) {
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
      }
      isStarted = false;
      releaseAlarm();
      releaseMediaBrowser();
    }

    public int getHour() {
      return getSharedPreferences().getInt(getString(R.string.key_alarm_hour), DEFAULT_TIME);
    }

    public int getMinute() {
      return getSharedPreferences().getInt(getString(R.string.key_alarm_minute), DEFAULT_TIME);
    }

    @Nullable
    public Radio getRadio() {
      return MainActivity.getRadios().getRadioFromURL(getSharedPreferences().getString(getString(R.string.key_alarm_radio), ""));
    }

    public boolean isStarted() {
      return isStarted;
    }

    @NonNull
    private SharedPreferences getSharedPreferences() {
      return AlarmService.this.getSharedPreferences(LOG_TAG, MODE_PRIVATE);
    }
  }

  private class MediaControllerCompatCallback extends MediaControllerCompat.Callback {
    // This might happen if the RadioService is killed while the Activity is in the
    // foreground and onStart() has been called (but not onStop())
    @Override
    public void onSessionDestroyed() {
      Log.d(LOG_TAG, "onSessionDestroyed");
      releaseMediaBrowser();
    }

    @Override
    public void onPlaybackStateChanged(@Nullable final PlaybackStateCompat state) {
      Log.d(LOG_TAG, "onPlaybackStateChanged: " + state);
      // If we are here, RadioService is started
      if (state != null) {
        releaseMediaBrowser();
      }
    }
  }

  private class MediaBrowserCompatConnectionCallback extends MediaBrowserCompat.ConnectionCallback {
    @Override
    public void onConnected() {
      // Get a MediaController for the MediaSession
      mediaController = new MediaControllerCompat(AlarmService.this, mediaBrowser.getSessionToken());
      // Link to the callback controller
      mediaController.registerCallback(mediaControllerCallback);
      // Launch radio
      if (!radioLaunch()) {
        // Something went wrong, cancel alarm
        ((AlarmServiceBinder) binder).cancelAlarm();
      }
    }

    @Override
    public void onConnectionSuspended() {
      if (mediaController != null) {
        mediaController.unregisterCallback(mediaControllerCallback);
      }
      mediaController = null;
    }

    @Override
    public void onConnectionFailed() {
      Log.d(LOG_TAG, "Connection to RadioService failed");
    }
  }
}