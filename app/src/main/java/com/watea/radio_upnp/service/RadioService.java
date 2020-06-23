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
import android.os.Handler;
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
import com.watea.radio_upnp.model.DlnaDevice;
import com.watea.radio_upnp.model.Radio;
import com.watea.radio_upnp.model.RadioLibrary;

import org.fourthline.cling.android.AndroidUpnpService;
import org.fourthline.cling.android.AndroidUpnpServiceImpl;
import org.fourthline.cling.controlpoint.ActionCallback;
import org.fourthline.cling.model.meta.Device;

import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Vector;

public class RadioService extends MediaBrowserServiceCompat implements PlayerAdapter.Listener {
  private static final String LOG_TAG = RadioService.class.getName();
  private static final int NOTIFICATION_ID = 9;
  private static final int REQUEST_CODE = 501;
  private static String CHANNEL_ID;
  private final UpnpServiceConnection upnpConnection = new UpnpServiceConnection();
  private final Handler handler = new Handler();
  private final UpnpActionControler upnpActionControler = new UpnpActionControler();
  private AndroidUpnpService androidUpnpService = null;
  private MediaSessionCompat session;
  private RadioLibrary radioLibrary;
  private NotificationManager notificationManager;
  private PlayerAdapter playerAdapter = null;
  private final VolumeProviderCompat volumeProviderCompat =
    new VolumeProviderCompat(VolumeProviderCompat.VOLUME_CONTROL_RELATIVE, 100, 50) {
      @Override
      public void onAdjustVolume(int direction) {
        playerAdapter.adjustVolume(direction);
      }
    };
  private HttpServer httpServer;
  private MediaMetadataCompat mediaMetadataCompat = null;
  private boolean isStarted;
  private String lockKey;

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
      Log.e(LOG_TAG, "NotificationManager error");
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
          Log.d(LOG_TAG, "New channel created");
        } else {
          Log.d(LOG_TAG, "Existing channel reused");
        }
      }
    }
    // Radio library access
    radioLibrary = new RadioLibrary(this);
    // Init HTTP Server
    RadioHandler radioHandler = new RadioHandler(getString(R.string.app_name), radioLibrary);
    HttpServer.Listener httpServerListener = new HttpServer.Listener() {
      @Override
      public void onError() {
        Log.d(LOG_TAG, "HTTP Server error");
        stopSelf();
      }
    };
    httpServer = new HttpServer(this, radioHandler, httpServerListener);
    // Is current handler listener
    radioHandler.setListener(playerAdapter);
    httpServer.start();
    // Bind to UPnP service, launch if not already
    if (!bindService(
      new Intent(this, AndroidUpnpServiceImpl.class),
      upnpConnection,
      BIND_AUTO_CREATE)) {
      Log.e(LOG_TAG, "Internal failure; AndroidUpnpService not bound");
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
  public void onDestroy() {
    super.onDestroy();
    Log.d(LOG_TAG, "onDestroy: requested...");
    if (playerAdapter != null) {
      playerAdapter.release();
    }
    upnpActionControler.release();
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
  public void onPlaybackStateChange(
    @NonNull final PlaybackStateCompat state, @NonNull final String lockKey) {
    handler.post(new Runnable() {
      @Override
      public void run() {
        if (isStillRunning(lockKey)) {
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
                ContextCompat.startForegroundService(
                  RadioService.this, new Intent(RadioService.this, RadioService.class));
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
    });
  }

  // Only if lockKey still valid
  @Override
  public void onInformationChange(
    @NonNull final MediaMetadataCompat mediaMetadataCompat, @NonNull final String lockKey) {
    handler.post(new Runnable() {
      @Override
      public void run() {
        if (isStillRunning(lockKey)) {
          session.setMetadata(RadioService.this.mediaMetadataCompat = mediaMetadataCompat);
          // Update notification
          notificationManager.notify(NOTIFICATION_ID, getNotification());
        }
      }
    });
  }

  private boolean isStillRunning(@NonNull String lockKey) {
    return session.isActive() && lockKey.equals(this.lockKey);
  }

  @Nullable
  private Notification getNotification() {
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
    NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
      .setStyle(new android.support.v4.media.app.NotificationCompat.MediaStyle()
        .setMediaSession(getSessionToken())
        .setShowActionsInCompactView(0))
      .setColor(ContextCompat.getColor(this, R.color.colorAccent))
      .setSmallIcon(R.drawable.ic_launcher_foreground)
      // Pending intent that is fired when user clicks on notification
      .setContentIntent(PendingIntent.getActivity(
        this,
        REQUEST_CODE,
        new Intent(this, MainActivity.class)
          .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_CANCEL_CURRENT))
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
            ActionStop : ActionPause : ActionPlay);
    if (mediaMetadataCompat == null) {
      Log.e(LOG_TAG, "getNotification: internal failure; no metadata defined for radio");
    } else {
      MediaDescriptionCompat description = mediaMetadataCompat.getDescription();
      builder
        .setLargeIcon(description.getIconBitmap())
        // Title, radio name
        .setContentTitle(description.getTitle())
        // Radio current track
        .setContentText(description.getSubtitle());
    }
    return builder.build();
  }

  private class UpnpServiceConnection implements ServiceConnection {
    @Override
    public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
      androidUpnpService = (AndroidUpnpService) iBinder;
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
      // Ensure robustness
      upnpActionControler.releaseActions(null);
      // Stop if any
      if (playerAdapter != null) {
        playerAdapter.stop();
      }
      // Synchronize session data
      session.setActive(true);
      session.setMetadata(mediaMetadataCompat = radio.getMediaMetadataBuilder().build());
      session.setExtras(extras);
      lockKey = UUID.randomUUID().toString();
      // Set actual player DLNA? Extra shall contain DLNA device UDN.
      if (extras.containsKey(getString(R.string.key_dlna_device))) {
        Device<?, ?, ?> chosenDevice = null;
        if (androidUpnpService != null) {
          for (Device<?, ?, ?> device : androidUpnpService.getRegistry().getDevices()) {
            if (DlnaDevice.getIdentity(device).equals(extras.getString(getString(R.string.key_dlna_device)))) {
              chosenDevice = device;
              break;
            }
          }
        }
        if (chosenDevice == null) {
          Log.e(LOG_TAG, "onPrepareFromMediaId: internal failure; can't process DLNA device");
          return;
        } else {
          playerAdapter = new UpnpPlayerAdapter(
            RadioService.this,
            httpServer,
            RadioService.this,
            radio,
            lockKey,
            chosenDevice,
            upnpActionControler);
          session.setPlaybackToRemote(volumeProviderCompat);
        }
      } else {
        playerAdapter = new LocalPlayerAdapter(
          RadioService.this,
          httpServer,
          RadioService.this,
          radio,
          lockKey);
        session.setPlaybackToLocal(AudioManager.STREAM_MUSIC);
      }
      // Prepare radio streaming
      playerAdapter.prepareFromMediaId();
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

  // Helper class for UPnP actions scheduling
  public class UpnpActionControler {
    private final Map<Radio, String> contentTypes = new Hashtable<>();
    private final Map<Device<?, ?, ?>, String> protocolInfos = new Hashtable<>();
    private final List<ActionCallback> actionCallbacks = new Vector<>();
    private boolean isActionRunning = false;

    @Nullable
    public String getContentType(@NonNull Radio radio) {
      return contentTypes.get(radio);
    }

    public void putContentType(@NonNull Radio radio, @NonNull String contentType) {
      contentTypes.put(radio, contentType);
    }

    @Nullable
    public String getProtocolInfo(@NonNull Device<?, ?, ?> device) {
      return protocolInfos.get(device);
    }

    public void putProtocolInfo(@NonNull Device<?, ?, ?> device, @NonNull String protocolInfo) {
      protocolInfos.put(device, protocolInfo);
    }

    // Execute asynchronous in the background
    public void executeAction(@NonNull ActionCallback actionCallback) {
      Log.d(LOG_TAG, "executeAction: " + actionCallback);
      if (androidUpnpService == null) {
        actionCallback.failure(actionCallback.getActionInvocation(), null, "UpnpService is null");
        release();
      } else {
        androidUpnpService.getControlPoint().execute(actionCallback);
      }
    }

    // Does nothing if an action is already running
    public void scheduleNextAction() {
      isActionRunning = false;
      scheduleAction(null);
    }

    public void scheduleAction(@Nullable final ActionCallback actionCallback) {
      handler.post(new Runnable() {
        @Override
        public void run() {
          if (actionCallback != null) {
            actionCallbacks.add(actionCallback);
          }
          if (!(actionCallbacks.isEmpty() || isActionRunning)) {
            executeAction(actionCallbacks.remove(0));
            isActionRunning = true;
          }
        }
      });
    }

    public void pushAction(@NonNull final ActionCallback actionCallback) {
      handler.post(new Runnable() {
        @Override
        public void run() {
          actionCallbacks.add(0, actionCallback);
        }
      });
    }

    // Remove remaining actions on device or all (device == null)
    public void releaseActions(@Nullable final Device<?, ?, ?> device) {
      handler.post(new Runnable() {
        @Override
        public void run() {
          if (device == null) {
            actionCallbacks.clear();
          } else {
            Iterator<ActionCallback> iter = actionCallbacks.iterator();
            while (iter.hasNext()) {
              if (iter.next().getActionInvocation().getAction().getService().getDevice() ==
                device) {
                iter.remove();
              }
            }
          }
          isActionRunning = false;
        }
      });
    }

    private void release() {
      contentTypes.clear();
      protocolInfos.clear();
      actionCallbacks.clear();
      isActionRunning = false;
    }
  }
}