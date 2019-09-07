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
import android.graphics.Color;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaBrowserServiceCompat;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.VolumeProviderCompat;
import android.support.v4.media.session.MediaButtonReceiver;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import com.watea.radio_upnp.R;
import com.watea.radio_upnp.activity.MainActivity;
import com.watea.radio_upnp.adapter.LocalPlayerAdapter;
import com.watea.radio_upnp.adapter.PlayerAdapter;
import com.watea.radio_upnp.adapter.UpnpPlayerAdapter;
import com.watea.radio_upnp.model.Radio;
import com.watea.radio_upnp.model.RadioLibrary;

import org.fourthline.cling.android.AndroidUpnpService;
import org.fourthline.cling.android.AndroidUpnpServiceImpl;

import java.util.List;

import static com.watea.radio_upnp.service.RadioHandler.Listener.VOID;

public class RadioService extends MediaBrowserServiceCompat implements PlayerAdapter.Listener {
  private static final String LOG_TAG = RadioService.class.getName();
  private static final int NOTIFICATION_ID = 9;
  private static final int REQUEST_CODE = 501;
  private static String CHANNEL_ID;
  private final UpnpServiceConnection upnpConnection = new UpnpServiceConnection();
  private MediaSessionCompat session;
  private RadioLibrary radioLibrary;
  private NotificationManager notificationManager;
  // Shall not be null
  private PlayerAdapter playerAdapter;
  private final VolumeProviderCompat volumeProviderCompat =
    new VolumeProviderCompat(VolumeProviderCompat.VOLUME_CONTROL_RELATIVE, 100, 50) {
      @Override
      public void onAdjustVolume(int direction) {
        playerAdapter.adjustVolume(direction);
      }
    };
  private LocalPlayerAdapter localPlayerAdapter;
  private UpnpPlayerAdapter upnpPlayerAdapter;
  private HttpServer httpServer;
  private MediaMetadataCompat mediaMetadataCompat = null;
  private boolean isStarted;

  @Override
  public void onCreate() {
    super.onCreate();
    // Create a new MediaSession...
    session = new MediaSessionCompat(this, LOG_TAG);
    // Link to callback where actual media controls are called...
    session.setCallback(new MediaSessionCompatCallback());
    session.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS |
      MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
    setSessionToken(session.getSessionToken());
    // Notification
    CHANNEL_ID = getResources().getString(R.string.app_name) + ".channel";
    notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    // Cancel all notifications to handle the case where the Service was killed and
    // restarted by the system
    if (notificationManager == null) {
      Log.e(LOG_TAG, "onCreate: NotificationManager error");
      stopSelf();
    } else {
      notificationManager.cancelAll();
      // Create the (mandatory) notification channel when running on Android Oreo and more
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        if (notificationManager.getNotificationChannel(CHANNEL_ID) == null) {
          NotificationChannel mChannel = new NotificationChannel(
            CHANNEL_ID,
            RadioService.class.getSimpleName(),
            NotificationManager.IMPORTANCE_LOW);
          // Configure the notification channel
          mChannel.setDescription(getString(R.string.radio_service_description)); // User-visible
          mChannel.enableLights(true);
          // Sets the notification light color for notifications posted to this
          // channel, if the device supports this feature
          mChannel.setLightColor(Color.GREEN);
          mChannel.enableVibration(true);
          mChannel.setVibrationPattern(new long[]{100, 200, 300, 400, 500, 400, 300, 200, 400});
          notificationManager.createNotificationChannel(mChannel);
          Log.d(LOG_TAG, "createChannel: new channel created");
        } else {
          Log.d(LOG_TAG, "createChannel: existing channel reused");
        }
      }
    }
    // Radio library access
    radioLibrary = new RadioLibrary(this);
    // Init HTTP Server
    RadioHandler radioHandler = new RadioHandler(getString(R.string.app_name), radioLibrary, true);
    HttpServer.Listener httpServerListener = new HttpServer.Listener() {
      @Override
      public void onError() {
        Log.d(LOG_TAG, "onCreate: HttpServer error");
        stopSelf();
      }
    };
    httpServer = new HttpServer(this, radioHandler, httpServerListener);
    // Init players
    localPlayerAdapter = new LocalPlayerAdapter(this, httpServer, this);
    upnpPlayerAdapter = new UpnpPlayerAdapter(this, httpServer, this);
    // Default actual player
    playerAdapter = localPlayerAdapter;
    // Is current handler listener
    radioHandler.setListener(playerAdapter);
    httpServer.start();
    // Bind to UPnP service, launch if not already
    if (!bindService(
      new Intent(this, AndroidUpnpServiceImpl.class),
      upnpConnection,
      BIND_AUTO_CREATE)) {
      Log.e(LOG_TAG, "UpnpPlayerAdapter: internal failure; AndroidUpnpService not bound");
    }
    isStarted = false;
    Log.d(LOG_TAG, "onCreate: done!");
  }

  @Override
  public BrowserRoot onGetRoot(
    @NonNull String clientPackageName, int clientUid, @Nullable Bundle rootHints) {
    return new BrowserRoot(radioLibrary.getRoot(), null);
  }

  @Override
  public void onLoadChildren(
    @NonNull final String parentMediaId,
    @NonNull final Result<List<MediaBrowserCompat.MediaItem>> result) {
    result.sendResult(parentMediaId.equals(radioLibrary.getRoot()) ?
      radioLibrary.getMediaItems() : null);
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    startForeground(NOTIFICATION_ID, getNotification());
    return START_REDELIVER_INTENT;
  }

  @Override
  public synchronized void onDestroy() {
    super.onDestroy();
    Log.d(LOG_TAG, "onDestroy: requested...");
    localPlayerAdapter.release();
    upnpPlayerAdapter.release();
    httpServer.stopServer();
    upnpConnection.release();
    radioLibrary.close();
    session.release();
    Log.d(LOG_TAG, "onDestroy: done!");
  }

  @Override
  public void onTaskRemoved(@NonNull Intent rootIntent) {
    super.onTaskRemoved(rootIntent);
    stopSelf();
  }

  // Only if lockKey still valid
  @SuppressLint("SwitchIntDef")
  @Override
  public synchronized void onPlaybackStateChange(
    @NonNull PlaybackStateCompat state, @NonNull String lockKey) {
    if (isValid(lockKey)) {
      Log.d(LOG_TAG, "New valid state/lock key received: " + state + "/" + lockKey);
      // Report the state to the MediaSession
      session.setPlaybackState(state);
      // Manage the started state of this service, and session activity
      switch (state.getState()) {
        case PlaybackStateCompat.STATE_BUFFERING:
          break;
        case PlaybackStateCompat.STATE_PLAYING:
          // Start service if needed
          if (isStarted) {
            notificationManager.notify(NOTIFICATION_ID, getNotification());
          } else {
            ContextCompat.startForegroundService(this, new Intent(this, RadioService.class));
            isStarted = true;
          }
          break;
        case PlaybackStateCompat.STATE_PAUSED:
          // Move service out started state
          stopForeground(false);
          // Update notification
          notificationManager.notify(NOTIFICATION_ID, getNotification());
          break;
        default:
          // Cancel session
          playerAdapter.release();
          session.setMetadata(mediaMetadataCompat = null);
          session.setActive(false);
          // Move service out started state
          if (isStarted) {
            stopForeground(true);
            stopSelf();
            isStarted = false;
          }
      }
    }
  }

  // Only if lockKey still valid
  @Override
  public synchronized void onInformationChange(
    @NonNull MediaMetadataCompat mediaMetadataCompat, @NonNull String lockKey) {
    if (isValid(lockKey)) {
      session.setMetadata(this.mediaMetadataCompat = mediaMetadataCompat);
      // Update notification
      notificationManager.notify(NOTIFICATION_ID, getNotification());
    }
  }

  private boolean isValid(@NonNull String lockKey) {
    return (!lockKey.equals(VOID) && playerAdapter.getLockKey().equals(lockKey));
  }

  @Nullable
  private Notification getNotification() {
    if (mediaMetadataCompat == null) {
      Log.e(LOG_TAG, "getNotification: internal failure; no metadata defined for radio");
      return null;
    }
    MediaDescriptionCompat description = mediaMetadataCompat.getDescription();
    NotificationCompat.Action ActionPause =
      new NotificationCompat.Action(
        R.drawable.ic_pause_black_24dp,
        getString(R.string.action_pause),
        MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PAUSE));
    NotificationCompat.Action ActionStop =
      new NotificationCompat.Action(
        R.drawable.ic_stop_black_24dp,
        getString(R.string.action_stop),
        MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_STOP));
    NotificationCompat.Action ActionPlay =
      new NotificationCompat.Action(
        R.drawable.ic_play_arrow_black_24dp,
        getString(R.string.action_play),
        MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PLAY));
    return new NotificationCompat.Builder(this, CHANNEL_ID)
      .setStyle(new android.support.v4.media.app.NotificationCompat.MediaStyle()
        .setMediaSession(getSessionToken())
        .setShowActionsInCompactView(0))
      .setColor(ContextCompat.getColor(this, R.color.colorAccent))
      .setLargeIcon(description.getIconBitmap())
      .setSmallIcon(R.drawable.ic_launcher_foreground)
      // Pending intent that is fired when user clicks on notification
      .setContentIntent(PendingIntent.getActivity(
        this,
        REQUEST_CODE,
        new Intent(this, MainActivity.class)
          .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_CANCEL_CURRENT))
      // Title, radio name
      .setContentTitle(description.getTitle())
      // Radio current track
      .setContentText(description.getSubtitle())
      // When notification is deleted (when playback is paused and notification can be
      // deleted) fire MediaButtonPendingIntent with ACTION_STOP
      .setDeleteIntent(MediaButtonReceiver.buildMediaButtonPendingIntent(
        this,
        PlaybackStateCompat.ACTION_STOP))
      // Show controls on lock screen even when user hides sensitive content.
      .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
      // UPnP device doesn't support PAUSE action but STOP
      .addAction(
        playerAdapter.isPlaying() ?
          playerAdapter instanceof UpnpPlayerAdapter ?
            ActionStop : ActionPause : ActionPlay)
      .build();
  }

  private class UpnpServiceConnection implements ServiceConnection {
    private AndroidUpnpService androidUpnpService = null;

    @Override
    public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
      upnpPlayerAdapter.setAndroidUpnpService(androidUpnpService = (AndroidUpnpService) iBinder);
    }

    @Override
    public void onServiceDisconnected(ComponentName componentName) {
      androidUpnpService = null;
    }

    void release() {
      if (androidUpnpService != null) {
        unbindService(upnpConnection);
        androidUpnpService = null;
      }
    }
  }

  // PlayerAdapter from session for actual media controls
  private class MediaSessionCompatCallback extends MediaSessionCompat.Callback {
    @Override
    public void onPrepareFromMediaId(@NonNull String mediaId, @NonNull Bundle extras) {
      // Radio retrieved in database
      Radio radio = radioLibrary.getFrom(Long.valueOf(mediaId));
      if (radio == null) {
        Log.e(LOG_TAG, "onPrepareFromMediaId: internal failure; can't retrieve radio");
        throw new RuntimeException();
      }
      // Stop if any, avoid any cross acquisition of multithread lock
      playerAdapter.stop();
      // Synchronize as session ids shall be changed in coherence
      synchronized (RadioService.this) {
        // Default
        playerAdapter = localPlayerAdapter;
        session.setPlaybackToLocal(AudioManager.STREAM_MUSIC);
        // Set actual player DLNA? Extra shall contain DLNA device UDN.
        if (!extras.isEmpty()) {
          if (upnpPlayerAdapter.setDlnaDevice(
            extras.getString(getString(R.string.key_dlna_device)))) {
            playerAdapter = upnpPlayerAdapter;
            session.setPlaybackToRemote(volumeProviderCompat);
          } else {
            Log.e(LOG_TAG, "onPrepareFromMediaId: internal failure; can't process DLNA device");
            return;
          }
        }
        // Synchronize session data
        session.setActive(true);
        session.setMetadata(mediaMetadataCompat = radio.getMediaMetadataBuilder().build());
        session.setExtras(extras);
        // Prepare radio streaming
        playerAdapter.prepareFromMediaId(radio);
      }
    }

    @Override
    public void onPlay() {
      playerAdapter.play();
    }

    @Override
    public void onPause() {
      playerAdapter.pause();
    }

    @Override
    public void onStop() {
      playerAdapter.stop();
    }
  }
}