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
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.RequiresApi;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaBrowserServiceCompat;
import android.support.v4.media.MediaDescriptionCompat;
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

public class RadioService extends MediaBrowserServiceCompat implements PlayerAdapter.Listener {
  private static final String LOG_TAG = RadioService.class.getSimpleName();
  private static final int NOTIFICATION_ID = 412;
  private static final int REQUEST_CODE = 501;
  private static String CHANNEL_ID;
  private final UpnpServiceConnection mUpnpConnection = new UpnpServiceConnection();
  private MediaSessionCompat mSession;
  private boolean mServiceInStartedState;
  private RadioLibrary mRadioLibrary;
  private NotificationManager mNotificationManager;
  private PlayerAdapter mPlayerAdapter;
  private LocalPlayerAdapter mLocalPlayerAdapter;
  private UpnpPlayerAdapter mUpnpPlayerAdapter;
  private HttpServer mHttpServer;

  @Override
  public void onCreate() {
    super.onCreate();
    CHANNEL_ID = getResources().getString(R.string.app_name) + ".channel";
    // Create a new MediaSession...
    mSession = new MediaSessionCompat(this, LOG_TAG);
    mServiceInStartedState = false;
    // Link to callback where actual media controls are called...
    mSession.setCallback(new MediaSessionCompatCallback());
    mSession.setFlags(
      MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS |
        MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
    setSessionToken(mSession.getSessionToken());
    // Notification
    mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    // Cancel all notifications to handle the case where the Service was killed and
    // restarted by the system
    if (mNotificationManager == null) {
      Log.d(LOG_TAG, "onCreate: NotificationManager error");
      stopSelf();
    } else {
      mNotificationManager.cancelAll();
    }
    // Radio library access
    mRadioLibrary = new RadioLibrary(this);
    // Init HTTP Server
    mHttpServer = new HttpServer(
      this,
      getString(R.string.app_name),
      mRadioLibrary,
      new HttpServer.Listener() {
        @Override
        public void onError() {
          Log.d(LOG_TAG, "onCreate: HttpServer error");
          stopSelf();
        }
      });
    mHttpServer.start();
    // Bind to UPnP service, launch if not already
    if (!bindService(
      new Intent(this, AndroidUpnpServiceImpl.class),
      mUpnpConnection,
      BIND_AUTO_CREATE)) {
      Log.e(LOG_TAG, "UpnpPlayerAdapter: internal failure; AndroidUpnpService not bound");
    }
    // Init players
    mLocalPlayerAdapter = new LocalPlayerAdapter(this, mHttpServer, this);
    mUpnpPlayerAdapter = new UpnpPlayerAdapter(this, mHttpServer, this);
    // Default actual player
    mPlayerAdapter = mLocalPlayerAdapter;
    // Create the (mandatory) notification channel when running on Android Oreo
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      createChannel();
    }
    Log.d(LOG_TAG, "onCreate: RadioService creating MediaSession, and MediaNotificationManager");
  }

  @Override
  public BrowserRoot onGetRoot(
    @NonNull String clientPackageName, int clientUid, @Nullable Bundle rootHints) {
    return new BrowserRoot(mRadioLibrary.getRoot(), null);
  }

  @Override
  public void onLoadChildren(
    @NonNull final String parentMediaId,
    @NonNull final Result<List<MediaBrowserCompat.MediaItem>> result) {
    result.sendResult(parentMediaId.equals(mRadioLibrary.getRoot()) ?
      mRadioLibrary.getMediaItems() : null);
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    mLocalPlayerAdapter.release();
    mUpnpPlayerAdapter.release();
    mHttpServer.stopServer();
    mUpnpConnection.release();
    mRadioLibrary.close();
    mSession.release();
    Log.d(LOG_TAG, "onDestroy: PlayerAdapter stopped, and MediaSession released");
  }

  @Override
  public void onTaskRemoved(@NonNull Intent rootIntent) {
    super.onTaskRemoved(rootIntent);
    stopSelf();
  }

  @SuppressLint("SwitchIntDef")
  @Override
  public void onPlaybackStateChange(@NonNull PlaybackStateCompat state) {
    // Report the state to the MediaSession
    mSession.setPlaybackState(state);
    // Manage the started state of this service
    switch (state.getState()) {
      case PlaybackStateCompat.STATE_BUFFERING:
        break;
      case PlaybackStateCompat.STATE_PLAYING:
        // Move service to started state
        if (!mServiceInStartedState) {
          ContextCompat.startForegroundService(
            this,
            new Intent(this, RadioService.class));
          mServiceInStartedState = true;
        }
        startForeground(NOTIFICATION_ID, getNotification());
        break;
      case PlaybackStateCompat.STATE_PAUSED:
        // Move service out started state
        stopForeground(false);
        // Update notification for pause
        mNotificationManager.notify(NOTIFICATION_ID, getNotification());
        break;
      case PlaybackStateCompat.STATE_ERROR:
      case PlaybackStateCompat.STATE_STOPPED:
        mSession.setMetadata(null);
      default:
        // Cancel session
        if (mSession.isActive()) {
          mSession.setActive(false);
        }
        // Move service out started state, if any
        if (mServiceInStartedState) {
          stopForeground(true);
          stopSelf();
          mServiceInStartedState = false;
        }
    }
  }

  @Override
  public void onInformationChange() {
    mSession.setMetadata(mPlayerAdapter.getCurrentMedia());
    // Update notification, if any
    if (mServiceInStartedState) {
      mNotificationManager.notify(NOTIFICATION_ID, getNotification());
    }
  }

  // Does nothing on versions of Android earlier than O
  @RequiresApi(Build.VERSION_CODES.O)
  private void createChannel() {
    if (mNotificationManager == null) {
      Log.d(LOG_TAG, "NotificationManager is not defined");
    } else {
      if (mNotificationManager.getNotificationChannel(CHANNEL_ID) == null) {
        NotificationChannel mChannel = new NotificationChannel(
          CHANNEL_ID,
          "RadioSession", // Name
          NotificationManager.IMPORTANCE_LOW);
        // Configure the notification channel,
        mChannel.setDescription("RadioSession and MediaPlayer"); // User-visible
        mChannel.enableLights(true);
        // Sets the notification light color for notifications posted to this
        // channel, if the device supports this feature
        mChannel.setLightColor(Color.GREEN);
        mChannel.enableVibration(true);
        mChannel.setVibrationPattern(new long[]{100, 200, 300, 400, 500, 400, 300, 200, 400});
        mNotificationManager.createNotificationChannel(mChannel);
        Log.d(LOG_TAG, "createChannel: new channel created");
      } else {
        Log.d(LOG_TAG, "createChannel: existing channel reused");
      }
    }
  }

  @Nullable
  private Notification getNotification() {
    if (mPlayerAdapter.getCurrentMedia() == null) {
      Log.e(LOG_TAG, "getNotification: internal failure; no metadata defined for radio");
      return null;
    }
    MediaDescriptionCompat description = mPlayerAdapter.getCurrentMedia().getDescription();
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
      // UPNP device doesn't support PAUSE action but STOP
      .addAction(
        mPlayerAdapter.isPlaying() ?
          mPlayerAdapter instanceof UpnpPlayerAdapter ?
            ActionStop : ActionPause : ActionPlay)
      .build();
  }

  private class UpnpServiceConnection implements ServiceConnection {
    private AndroidUpnpService mAndroidUpnpService = null;

    @Override
    public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
      mUpnpPlayerAdapter.setAndroidUpnpService(mAndroidUpnpService = (AndroidUpnpService) iBinder);
    }

    @Override
    public void onServiceDisconnected(ComponentName componentName) {
      mAndroidUpnpService = null;
    }

    void release() {
      if (mAndroidUpnpService != null) {
        unbindService(mUpnpConnection);
        mAndroidUpnpService = null;
      }
    }
  }

  // PlayerAdapter from session for actual media controls
  private class MediaSessionCompatCallback extends MediaSessionCompat.Callback {
    @Override
    public void onPrepareFromMediaId(@NonNull String mediaId, @NonNull Bundle extras) {
      // Stop if any
      mPlayerAdapter.stop();
      // Set actual player: local or DLNA?
      if (extras.isEmpty()) {
        mPlayerAdapter = mLocalPlayerAdapter;
      } else {
        // Extra shall contain DLNA device UDN
        if (mUpnpPlayerAdapter.setDlnaDevice(
          extras.getString(getString(R.string.key_dlna_device)))) {
          mPlayerAdapter = mUpnpPlayerAdapter;
        } else {
          Log.e(LOG_TAG, "onPrepareFromMediaId: internal failure; can't process DLNA device");
          return;
        }
      }
      // Activate session
      if (!mSession.isActive()) {
        mSession.setActive(true);
      }
      // Prepare radio streaming, radio retrieved in database
      Radio radio = mRadioLibrary.getFrom(Long.valueOf(mediaId));
      if (radio == null) {
        Log.e(LOG_TAG, "onPrepareFromMediaId: internal failure; can't retrieve radio");
        throw new RuntimeException();
      }
      mPlayerAdapter.prepareFromMediaId(radio);
      // Synchronize session data
      mSession.setMetadata(mPlayerAdapter.getCurrentMedia());
      mSession.setExtras(extras);
    }

    @Override
    public void onPlay() {
      mPlayerAdapter.play();
    }

    @Override
    public void onPause() {
      mPlayerAdapter.pause();
    }

    @Override
    public void onStop() {
      mPlayerAdapter.stop();
    }
  }
}