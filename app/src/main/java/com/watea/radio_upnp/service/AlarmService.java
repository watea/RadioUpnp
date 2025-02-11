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
import android.provider.Settings;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.session.MediaControllerCompat;
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
  private String CHANNEL_ID;
  private MediaBrowserCompat mediaBrowser = null;
  private AlarmManager alarmManager;  // Callback from media control
  @Nullable
  private MediaControllerCompat mediaController = null;

  @Override
  public void onCreate() {
    super.onCreate();
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
  }  private final MediaControllerCompat.Callback mediaControllerCallback =
    new MediaControllerCompat.Callback() {
      // This might happen if the RadioService is killed while the Activity is in the
      // foreground and onStart() has been called (but not onStop())
      @Override
      public void onSessionDestroyed() {
        mediaBrowserDisconnect();
        Log.d(LOG_TAG, "onSessionDestroyed: RadioService is dead!!!");
      }
    };

  @Override
  public void onDestroy() {
    super.onDestroy();
    mediaBrowserDisconnect();
  }  // MediaController from the MediaBrowser when it has successfully connected

  @Nullable
  @Override
  public IBinder onBind(Intent intent) {
    return binder;
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent.getBooleanExtra(getString(R.string.key_alarm), false)) {
      // Launch RadioService, may fail if already called and connection not ended
      try {
        mediaBrowser.connect();
      } catch (IllegalStateException illegalStateException) {
        Log.e(LOG_TAG, "onCreate: mediaBrowser.connect() failed", illegalStateException);
      }
      //((AlarmServiceBinder) binder).rescheduleAlarm();
      return super.onStartCommand(intent, flags, startId);
    }
    startForeground(NOTIFICATION_ID, getNotification());
    return START_STICKY;
  }

  private Notification getNotification() {
    return new NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_baseline_alarm_white_24dp)
      .setContentTitle(getString(R.string.alarm_set) + ((AlarmServiceBinder) binder).getHour() + ":" + String.format(Locale.getDefault(), "%02d", ((AlarmServiceBinder) binder).getMinute()))
      .build();
  }

  private void mediaBrowserDisconnect() {
    // Disconnect mediaBrowser
    mediaBrowser.disconnect();
    // Forced suspended connection
    mediaBrowserConnectionCallback.onConnectionSuspended();
  }  private final MediaBrowserCompat.ConnectionCallback mediaBrowserConnectionCallback =
    new MediaBrowserCompat.ConnectionCallback() {
      @Override
      public void onConnected() {
        // Get a MediaController for the MediaSession
        mediaController = new MediaControllerCompat(AlarmService.this, mediaBrowser.getSessionToken());
        // Launch radio
        Radio radio = MainActivity.getRadios().getRadioFromURL(AlarmService.this.getURL());
        // Last chance
        if (radio == null) {
          radio = MainActivity.getRadios().isEmpty() ? null : MainActivity.getRadios().get(0);
        }
        if (radio == null) {
          Log.e(LOG_TAG, "onConnected: alarm radio is null!");
        } else {
          mediaController.getTransportControls().prepareFromMediaId(radio.getId(), new Bundle());
        }
      }

      @Override
      public void onConnectionSuspended() {
        if (mediaController != null) {
          mediaController.unregisterCallback(mediaControllerCallback);
        }
      }

      @Override
      public void onConnectionFailed() {
        Log.d(LOG_TAG, "Connection to RadioService failed");
      }
    };

  private String getURL() {
    return ((AlarmServiceBinder) binder).getSharedPreferences().getString(getString(R.string.key_last_played_radio), "");
  }

  public static class AlarmReceiver extends BroadcastReceiver {
    private static final String LOG_TAG = AlarmService.class.getSimpleName();

    @Override
    public void onReceive(Context context, Intent intent) {
      Log.d(LOG_TAG, "onReceive");
      final Intent serviceIntent = new Intent(context, AlarmService.class);
      serviceIntent.putExtra(context.getString(R.string.key_alarm), true);
      context.startService(serviceIntent);
    }
  }

  public class AlarmServiceBinder extends android.os.Binder {
    public void setAlarm(int hour, int minute, @NonNull String radioURL) {
      final Calendar calendar = Calendar.getInstance();
      calendar.set(Calendar.HOUR_OF_DAY, hour);
      calendar.set(Calendar.MINUTE, minute);
      calendar.set(Calendar.SECOND, 0);
      if (Calendar.getInstance().after(calendar)) {
        calendar.add(Calendar.DAY_OF_MONTH, 1);
      }
      try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
          if (alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(
              AlarmManager.RTC_WAKEUP,
              calendar.getTimeInMillis(),
              getPendingIntent());
          } else {
            final Intent settingsIntent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
            settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(settingsIntent);
            Log.e(LOG_TAG, "Permission to schedule exact alarms not granted.");
          }
        } else {
          alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            calendar.getTimeInMillis(),
            getPendingIntent());
        }
      } catch (SecurityException securityException) {
        Log.e(LOG_TAG, "SecurityException: Unable to set exact alarm.", securityException);
      }
      getSharedPreferences()
        .edit()
        .putString(getString(R.string.key_last_played_radio), radioURL)
        .putInt(getString(R.string.key_alarm_hour), hour)
        .putInt(getString(R.string.key_alarm_minute), minute)
        .putBoolean(getString(R.string.key_alarm_enabled), true)
        .apply();
      startForegroundService(new Intent(AlarmService.this, AlarmService.class));
    }

    public void rescheduleAlarm() {
      setAlarm(getHour(), getMinute(), getURL());
    }

    public void cancelAlarm() {
      mediaBrowserDisconnect();
      alarmManager.cancel(getPendingIntent());
      getSharedPreferences()
        .edit()
        .putBoolean(getString(R.string.key_alarm_enabled), false)
        .apply();
      stopForeground(STOP_FOREGROUND_REMOVE);
      stopSelf();
    }

    public int getHour() {
      return getSharedPreferences().getInt(getString(R.string.key_alarm_hour), DEFAULT_TIME);
    }

    public int getMinute() {
      return getSharedPreferences().getInt(getString(R.string.key_alarm_minute), DEFAULT_TIME);
    }

    public boolean isEnabled() {
      return getSharedPreferences().getBoolean(getString(R.string.key_alarm_enabled), false);
    }

    @NonNull
    private PendingIntent getPendingIntent() {
      return PendingIntent.getBroadcast(
        AlarmService.this,
        0,
        new Intent(AlarmService.this, AlarmReceiver.class),
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @NonNull
    private SharedPreferences getSharedPreferences() {
      return AlarmService.this.getSharedPreferences(getString(R.string.key_preference_file), MODE_PRIVATE);
    }
  }




}