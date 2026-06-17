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
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.car.app.connection.CarConnection;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.lifecycle.Observer;
import androidx.media3.common.MediaItem;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;

import com.google.common.util.concurrent.ListenableFuture;
import com.watea.radio_upnp.R;
import com.watea.radio_upnp.model.Radio;
import com.watea.radio_upnp.model.Radios;

import java.util.Calendar;

public class AlarmService extends Service {
  private static final String LOG_TAG = AlarmService.class.getSimpleName();
  private static final Handler HANDLER = new Handler(Looper.getMainLooper());
  private static final int NOTIFICATION_ID = 10;
  private static final int DEFAULT_TIME = -1;
  private static final String ALARM_TRIGGERED = "com.watea.radio_upnp.ALARM_TRIGGERED";
  private static final String ALARM_CANCEL = "com.watea.radio_upnp.ALARM_CANCEL";
  private final Binder binder = new AlarmServiceBinder();
  private final NetworkRequest networkRequest = new NetworkRequest.Builder()
    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    .build();
  // Wait for internet connection
  private final ConnectivityManagerNetworkCallback networkCallback = new ConnectivityManagerNetworkCallback();
  private String channelId;
  @Nullable
  private ListenableFuture<MediaController> controllerFuture = null;
  @Nullable
  private MediaController mediaController = null;
  private SessionToken sessionToken;
  private AlarmManager alarmManager;
  private ConnectivityManager connectivityManager;
  private boolean isStarted = false;
  private boolean isAndroidAutoConnected = false;
  private final Observer<Integer> carConnectionObserver = type -> {
    final boolean connected = (type == CarConnection.CONNECTION_TYPE_PROJECTION);
    isAndroidAutoConnected = connected;
    Log.d(LOG_TAG, "Android Auto " + (connected ? "CONNECTED" : "DISCONNECTED"));
  };
  @Nullable
  private CarConnection carConnection = null;

  @Override
  public void onCreate() {
    super.onCreate();
    Log.d(LOG_TAG, "onCreate");
    // Notification
    channelId = getResources().getString(R.string.app_name) + "." + LOG_TAG;
    final NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this);
    // Cancel all notifications to handle the case where the Service was killed and
    // restarted by the system
    notificationManager.cancelAll();
    // Create the (mandatory) notification channel
    if (notificationManager.getNotificationChannel(channelId) == null) {
      final NotificationChannel notificationChannel = new NotificationChannel(
        channelId,
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
    // RadioService session token
    sessionToken = new SessionToken(this, new ComponentName(this, RadioService.class));
    // AlarmManager
    alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
    // ConnectivityManager
    connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
    // Android Auto
    carConnection = new CarConnection(this);
    carConnection.getType().observeForever(carConnectionObserver);
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    Log.d(LOG_TAG, "onDestroy");
    // Robustness
    releaseAlarm();
    releaseController();
    releaseConnectivityManagerCallback();
    if (carConnection != null) {
      carConnection.getType().removeObserver(carConnectionObserver);
    }
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
    if (intent == null) {
      return super.onStartCommand(null, flags, startId);
    }
    if (ALARM_CANCEL.equals(intent.getAction())) {
      ((AlarmServiceBinder) binder).cancelAlarm();
      return super.onStartCommand(intent, flags, startId);
    } else if (ALARM_TRIGGERED.equals(intent.getAction())) {
      // Wake lock
      final PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
      final PowerManager.WakeLock wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, LOG_TAG);
      wakeLock.acquire(10 * 60 * 1000L); // 10 min.
      // Register network callback
      connectivityManager.registerNetworkCallback(networkRequest, networkCallback);
      // Relaunch alarm
      final AlarmServiceBinder alarmServiceBinder = (AlarmServiceBinder) binder;
      if (!setAlarmManagerAlarm(alarmServiceBinder.getHour(), alarmServiceBinder.getMinute(), true)) {
        alarmServiceBinder.cancelAlarm();
      }
      return super.onStartCommand(intent, flags, startId);
    } else {
      if (!isStarted) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          startForeground(
            NOTIFICATION_ID,
            getNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
          startForeground(NOTIFICATION_ID, getNotification());
        }
        isStarted = true;
      }
      return START_STICKY;
    }
  }

  private boolean setAlarmManagerAlarm(int hour, int minute, boolean isRelaunch) {
    Log.d(LOG_TAG, "setAlarmManagerAlarm");
    if ((Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) && !alarmManager.canScheduleExactAlarms()) {
      return false;
    }
    final Calendar calendar = Calendar.getInstance();
    calendar.set(Calendar.HOUR_OF_DAY, hour);
    calendar.set(Calendar.MINUTE, minute);
    calendar.set(Calendar.SECOND, 0);
    // If the time is in the past, add one day
    if (isRelaunch || (calendar.getTimeInMillis() <= System.currentTimeMillis())) {
      calendar.add(Calendar.DAY_OF_YEAR, 1);
    }
    // Set
    final PendingIntent pendingIntent = getPendingIntent();
    final AlarmManager.AlarmClockInfo alarmClockInfo = new AlarmManager.AlarmClockInfo(calendar.getTimeInMillis(), pendingIntent);
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
    return new NotificationCompat.Builder(this, channelId)
      .setSmallIcon(R.drawable.ic_mic_white_24dp)
      .setContentTitle(getString(R.string.alarm_title))
      .setContentText(getString(R.string.alarm_set_for, alarmServiceBinder.getHour(), alarmServiceBinder.getMinute()) + " / " + radioName)
      .addAction(R.drawable.ic_stop_white_24dp, getString(android.R.string.cancel), getCancelPendingIntent())
      .build();
  }

  private void releaseController() {
    // Robustness: force suspended connection
    if (mediaController != null) {
      mediaController.release();
      mediaController = null;
    }
    if (controllerFuture != null) {
      MediaController.releaseFuture(controllerFuture);
      controllerFuture = null;
    }
  }

  private void releaseAlarm() {
    alarmManager.cancel(getPendingIntent());
  }

  private void releaseConnectivityManagerCallback() {
    try {
      connectivityManager.unregisterNetworkCallback(networkCallback);
    } catch (IllegalArgumentException illegalArgumentException) {
      Log.d(LOG_TAG, "releaseConnectivityManagerCallback failed");
    }
  }

  @NonNull
  private PendingIntent getCancelPendingIntent() {
    return PendingIntent.getForegroundService(
      this,
      1,
      new Intent(ALARM_CANCEL, null, this, AlarmService.class),
      PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  @NonNull
  private PendingIntent getPendingIntent() {
    return PendingIntent.getBroadcast(
      this,
      0,
      new Intent(this, AlarmReceiver.class),
      PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  public interface Listener {
    void onAlarmCancelled();
  }

  public static class AlarmReceiver extends BroadcastReceiver {
    private static final String LOG_TAG = AlarmReceiver.class.getSimpleName();

    @Override
    public void onReceive(Context context, Intent intent) {
      Log.d(LOG_TAG, "onReceive");
      context.startForegroundService(new Intent(ALARM_TRIGGERED).setClass(context, AlarmService.class));
    }
  }

  public class AlarmServiceBinder extends android.os.Binder {
    @Nullable
    private Listener listener = null;

    public void setListener(@Nullable Listener listener) {
      this.listener = listener;
    }

    public boolean setAlarm(int hour, int minute, @NonNull String radioURL) {
      getSharedPreferences()
        .edit()
        .putString(getString(R.string.key_alarm_radio), radioURL)
        .putInt(getString(R.string.key_alarm_hour), hour)
        .putInt(getString(R.string.key_alarm_minute), minute)
        .apply();
      releaseAlarm(); // Robustness
      if (setAlarmManagerAlarm(hour, minute, false)) {
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
      releaseController();
      if (listener != null) {
        listener.onAlarmCancelled();
      }
    }

    public int getHour() {
      return getSharedPreferences().getInt(getString(R.string.key_alarm_hour), DEFAULT_TIME);
    }

    public int getMinute() {
      return getSharedPreferences().getInt(getString(R.string.key_alarm_minute), DEFAULT_TIME);
    }

    @Nullable
    public Radio getRadio() {
      return Radios.getInstance().getRadioFromURL(getSharedPreferences().getString(getString(R.string.key_alarm_radio), ""));
    }

    public boolean isStarted() {
      return isStarted;
    }

    @NonNull
    private SharedPreferences getSharedPreferences() {
      return AlarmService.this.getSharedPreferences(LOG_TAG, MODE_PRIVATE);
    }
  }

  private class ConnectivityManagerNetworkCallback extends ConnectivityManager.NetworkCallback {
    // Callback from media control.
    // This might happen if the RadioService is killed while the Activity is in the
    // foreground and onStart() has been called (but not onStop()).
    private final MediaController.Listener mediaControllerListener = new MediaController.Listener() {
      @Override
      public void onDisconnected(@NonNull MediaController controller) {
        Log.d(LOG_TAG, "onDisconnected");
        mediaController = null;
      }
    };

    @Override
    public void onAvailable(@NonNull Network network) {
      Log.d(LOG_TAG, "onAvailable");
      HANDLER.post(() -> {
        // Build on main thread so MediaController is bound to the main looper
        controllerFuture = new MediaController.Builder(AlarmService.this, sessionToken)
          .setListener(mediaControllerListener)
          .buildAsync();
        controllerFuture.addListener(new MediaBrowserConnectionCallback(), HANDLER::post);
        releaseConnectivityManagerCallback();
      });
    }

    @Override
    public void onLost(@NonNull Network network) {
      Log.d(LOG_TAG, "onLost");
    }
  }

  // Runnable executed when controller future completes
  private class MediaBrowserConnectionCallback implements Runnable {
    @Override
    public void run() {
      if (controllerFuture == null) {
        return;
      }
      try {
        // Get a MediaController for the MediaSession
        mediaController = controllerFuture.get();
        Log.d(LOG_TAG, "onConnected");
        // Launch radio
        if (Radios.isInit()) {
          launch();
        } else {
          // Robustness
          Radios.getInstance().addListener(new Radios.Listener() {
            @Override
            public void onInitEnd() {
              launch();
              Radios.getInstance().removeListener(this);
            }
          });
        }
      } catch (Exception exception) {
        Log.e(LOG_TAG, "MediaController connection failed", exception);
      }
    }

    private void launch() {
      if (isAndroidAutoConnected) {
        Log.d(LOG_TAG, "launch: Android Auto is connected => no launch");
      } else {
        final Radio radio = ((AlarmServiceBinder) binder).getRadio();
        if (radio == null) {
          Log.e(LOG_TAG, "launch: alarm radio is null");
        } else if (mediaController == null) {
          Log.e(LOG_TAG, "launch: mediaController is null");
        } else {
          Log.d(LOG_TAG, "launch: alarm on radio => " + radio.getName());
          mediaController.addMediaItem(new MediaItem.Builder().setMediaId(radio.getId()).build());
          releaseController();
          return;
        }
      }
      // Something went wrong, cancel alarm
      ((AlarmServiceBinder) binder).cancelAlarm();
    }
  }
}