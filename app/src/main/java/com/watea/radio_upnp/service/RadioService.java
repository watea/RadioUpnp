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
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.media.MediaBrowserServiceCompat;
import androidx.media.VolumeProviderCompat;
import androidx.media.session.MediaButtonReceiver;

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
import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.model.meta.Action;
import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.model.meta.Service;

import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Vector;

public class RadioService
  extends MediaBrowserServiceCompat
  implements PlayerAdapter.Listener, RadioHandler.Listener {
  private static final String LOG_TAG = RadioService.class.getName();
  private static final int NOTIFICATION_ID = 9;
  private static final int REQUEST_CODE = 501;
  private static final Handler handler = new Handler(Looper.getMainLooper());
  private static String CHANNEL_ID;
  private final UpnpServiceConnection upnpConnection = new UpnpServiceConnection();
  private final UpnpActionController upnpActionController = new UpnpActionController();
  private Radio radio = null;
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
  private boolean isAllowedToRelaunch;
  private String lockKey;
  private NotificationCompat.Action actionPause;
  private NotificationCompat.Action actionStop;
  private NotificationCompat.Action actionPlay;
  private MediaControllerCompat mediaController;

  @Override
  public void onCreate() {
    super.onCreate();
    // Create a new MediaSession and controller...
    session = new MediaSessionCompat(this, LOG_TAG);
    mediaController = session.getController();
    // Link to callback where actual media controls are called...
    session.setCallback(new MediaSessionCompatCallback());
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
          NotificationChannel notificationChannel = new NotificationChannel(
            CHANNEL_ID,
            RadioService.class.getSimpleName(),
            NotificationManager.IMPORTANCE_LOW);
          // Configure the notification channel
          notificationChannel.setDescription(getString(R.string.radio_service_description)); // User-visible
          notificationChannel.enableLights(true);
          // Sets the notification light color for notifications posted to this
          // channel, if the device supports this feature
          notificationChannel.setLightColor(Color.GREEN);
          notificationChannel.enableVibration(true);
          notificationChannel.setVibrationPattern(
            new long[]{100, 200, 300, 400, 500, 400, 300, 200, 400});
          notificationManager.createNotificationChannel(notificationChannel);
          Log.d(LOG_TAG, "New channel created");
        } else {
          Log.d(LOG_TAG, "Existing channel reused");
        }
      }
    }
    // Radio library access
    radioLibrary = new RadioLibrary(this);
    // Init HTTP Server
    httpServer = new HttpServer(
      this,
      getString(R.string.app_name),
      radioLibrary,
      this,
      () -> {
        Log.d(LOG_TAG, "HTTP Server error");
        stopSelf();
      });
    httpServer.start();
    // Bind to UPnP service, launch if not already
    if (!bindService(
      new Intent(this, AndroidUpnpServiceImpl.class),
      upnpConnection,
      BIND_AUTO_CREATE)) {
      Log.e(LOG_TAG, "Internal failure; AndroidUpnpService not bound");
    }
    isStarted = false;
    isAllowedToRelaunch = false;
    // Prepare notification
    actionPause = new NotificationCompat.Action(
      R.drawable.ic_pause_white_24dp,
      getString(R.string.action_pause),
      MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PAUSE));
    actionStop = new NotificationCompat.Action(
      R.drawable.ic_stop_white_24dp,
      getString(R.string.action_stop),
      MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_STOP));
    actionPlay = new NotificationCompat.Action(
      R.drawable.ic_play_arrow_white_24dp,
      getString(R.string.action_play),
      MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PLAY));
    Log.d(LOG_TAG, "onCreate: done!");
  }

  // Not used by app
  @Override
  public BrowserRoot onGetRoot(
    @NonNull String clientPackageName, int clientUid, @Nullable Bundle rootHints) {
    return new BrowserRoot(radioLibrary.getRoot(), null);
  }

  // Not used by app
  @Override
  public void onLoadChildren(
    @NonNull final String parentMediaId,
    @NonNull final Result<List<MediaBrowserCompat.MediaItem>> result) {
    result.sendResult(parentMediaId.equals(radioLibrary.getRoot()) ?
      radioLibrary.getMediaItems() : null);
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    Log.d(LOG_TAG, "onDestroy: requested...");
    if (playerAdapter != null) {
      playerAdapter.release();
    }
    upnpActionController.release();
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

  @Override
  public void onNewInformation(
    @NonNull final String information,
    @Nullable final String rate,
    @NonNull final String lockKey) {
    // We add current radio information to current media data
    handler.post(() -> {
      if (hasLockKey(lockKey) && (radio != null)) {
        session.setMetadata(RadioService.this.mediaMetadataCompat =
          radio
            .getMediaMetadataBuilder()
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, information)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, information)
            // Use WRITER for rate
            .putString(MediaMetadataCompat.METADATA_KEY_WRITER, rate)
            .build());
        // Update notification
        notificationManager.notify(NOTIFICATION_ID, getNotification());
      }
    });
  }

  @Override
  public void onError(@NonNull String lockKey) {
    Log.d(LOG_TAG, "RadioHandler error received");
    onPlaybackStateChange(
      PlayerAdapter.getPlaybackStateCompat(PlaybackStateCompat.STATE_ERROR).build(), lockKey);
  }

  @Override
  public boolean hasLockKey(@NonNull String lockKey) {
    return session.isActive() && lockKey.equals(this.lockKey);
  }

  @Override
  public boolean isUpnp() {
    return (playerAdapter != null) && (playerAdapter instanceof UpnpPlayerAdapter);
  }

  @Override
  public boolean isPaused() {
    return
      (session.getController().getPlaybackState().getState() == PlaybackStateCompat.STATE_PAUSED);
  }

  // Only if lockKey still valid
  @SuppressLint("SwitchIntDef")
  @Override
  public void onPlaybackStateChange(
    @NonNull final PlaybackStateCompat state, @NonNull final String lockKey) {
    handler.post(() -> {
      if (hasLockKey(lockKey)) {
        Log.d(LOG_TAG, "New valid state/lock key received: " + state + "/" + lockKey);
        // Report the state to the MediaSession
        session.setPlaybackState(state);
        // Manage the started state of this service, and session activity
        switch (state.getState()) {
          case PlaybackStateCompat.STATE_BUFFERING:
            break;
          case PlaybackStateCompat.STATE_PLAYING:
            // Start service if needed
            if (!isStarted) {
              ContextCompat.startForegroundService(
                RadioService.this, new Intent(RadioService.this, RadioService.class));
              isStarted = true;
            }
            startForeground(NOTIFICATION_ID, getNotification());
            isAllowedToRelaunch = true;
            break;
          case PlaybackStateCompat.STATE_PAUSED:
            // No relaunch on pause
            isAllowedToRelaunch = false;
            // Move service out started state
            stopForeground(false);
            // Update notification
            notificationManager.notify(NOTIFICATION_ID, getNotification());
            break;
          case PlaybackStateCompat.STATE_ERROR:
            if (playerAdapter instanceof LocalPlayerAdapter) {
              // Try to relaunch local player, just once
              if (isAllowedToRelaunch) {
                isAllowedToRelaunch = false;
                handler.postDelayed(this::relaunch, 4000);
                break;
              }
            }
          default:
            // Cancel session
            isAllowedToRelaunch = false;
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
    });
  }

  @SuppressLint("UnspecifiedImmutableFlag")
  @NonNull
  private Notification getNotification() {
    NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID);
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
    builder.setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
      .setMediaSession(getSessionToken())
      .setShowActionsInCompactView(0))
      .setSmallIcon(R.drawable.ic_baseline_mic_white_24dp)
      // Pending intent that is fired when user clicks on notification
      .setContentIntent(PendingIntent.getActivity(
        this,
        REQUEST_CODE,
        new Intent(this, MainActivity.class)
          .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_CANCEL_CURRENT))
      // When notification is deleted (when playback is paused and notification can be
      // deleted) fire MediaButtonPendingIntent with ACTION_STOP
      .setDeleteIntent(
        MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_STOP))
      // Show controls on lock screen even when user hides sensitive content
      .setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
    // UPnP device doesn't support PAUSE action but STOP
    if (mediaController == null) {
      Log.e(LOG_TAG, "getNotification: internal failure; no mediaController");
    } else {
      boolean isPlaying =
        (mediaController.getPlaybackState().getState() == PlaybackStateCompat.STATE_PLAYING);
      builder
        .addAction(isPlaying ?
          playerAdapter instanceof UpnpPlayerAdapter ? actionStop : actionPause : actionPlay)
        .setOngoing(isPlaying);
    }
    return builder.build();
  }

  @NonNull
  private String getMediaId() {
    return mediaController.getMetadata().getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID);
  }

  private boolean relaunch() {
    if ((mediaController != null) &&
      (mediaController.getPlaybackState().getState() == PlaybackStateCompat.STATE_ERROR)) {
      mediaController
        .getTransportControls()
        .prepareFromMediaId(getMediaId(), mediaController.getExtras());
      return true;
    }
    return false;
  }

  public static abstract class UpnpAction {
    @Nullable
    private final Action<?> action;

    public UpnpAction(@Nullable Service<?, ?> service, @NonNull String actionName) {
      if (service == null) {
        Log.d(LOG_TAG, "Service not available for: " + actionName);
        action = null;
      } else {
        action = service.getAction(actionName);
      }
      if (action == null) {
        Log.d(LOG_TAG, "Action not available: " + actionName);
      }
    }

    public void execute(
      @NonNull UpnpActionController upnpActionController, boolean isScheduled) {
      AndroidUpnpService androidUpnpService = upnpActionController.getAndroidUpnpService();
      if (androidUpnpService == null) {
        Log.d(LOG_TAG, "Try to execute UPnP action with no service");
        if (isScheduled) {
          upnpActionController.release();
        }
      } else {
        ActionCallback actionCallback =
          new RadioService.UpnpActionCallback(this, getActionInvocation());
        Log.d(LOG_TAG, "Execute: " + actionCallback);
        androidUpnpService.getControlPoint().execute(actionCallback);
      }
    }

    @NonNull
    public Device<?, ?, ?> getDevice() {
      assert action != null;
      return action.getService().getDevice();
    }

    public boolean isAvailable() {
      return (action != null);
    }

    @NonNull
    protected ActionInvocation<?> getActionInvocation(@Nullable String instanceId) {
      assert action != null;
      ActionInvocation<?> actionInvocation = new ActionInvocation<>(action);
      if (instanceId != null) {
        actionInvocation.setInput("InstanceID", instanceId);
      }
      return actionInvocation;
    }

    protected abstract ActionInvocation<?> getActionInvocation();

    protected abstract void success(@NonNull ActionInvocation<?> actionInvocation);

    protected abstract void failure();
  }

  public static class UpnpActionCallback extends ActionCallback {
    @NonNull
    private final UpnpAction upnpAction;

    public UpnpActionCallback(
      @NonNull UpnpAction upnpAction, @NonNull ActionInvocation<?> actionInvocation) {
      super(actionInvocation);
      this.upnpAction = upnpAction;
    }

    @Override
    public void success(ActionInvocation actionInvocation) {
      Log.d(LOG_TAG, "Successfully called UPnP action: " + actionInvocation.getAction().getName());
      upnpAction.success(actionInvocation);
    }

    @Override
    public void failure(
      ActionInvocation actionInvocation, UpnpResponse operation, String defaultMsg) {
      Log.d(LOG_TAG, "UPnP error: " + actionInvocation.getAction().getName() + " => " + defaultMsg);
      upnpAction.failure();
    }
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
      // Ensure robustness
      upnpActionController.releaseActions(null);
      // In any case, stop player if it exists
      if (playerAdapter != null) {
        playerAdapter.stop();
      }
      // Radio retrieved in database
      radio = radioLibrary.getFrom(Long.valueOf(mediaId));
      // If radio is not found, abort (may happen if radio were deleted by user)
      if (radio == null) {
        Log.d(LOG_TAG, "onPrepareFromMediaId: radio not found");
        return;
      }
      // Set actual player DLNA? Extra shall contain DLNA device UDN.
      boolean isDlna = extras.containsKey(getString(R.string.key_dlna_device));
      Device<?, ?, ?> chosenDevice = isDlna ?
        getChosenDevice(extras.getString(getString(R.string.key_dlna_device))) : null;
      if (isDlna && (chosenDevice == null)) {
        Log.e(LOG_TAG, "onPrepareFromMediaId: internal failure; can't process DLNA device");
        return;
      }
      // Synchronize session data
      session.setActive(true);
      session.setMetadata(mediaMetadataCompat = radio.getMediaMetadataBuilder().build());
      session.setExtras(extras);
      lockKey = UUID.randomUUID().toString();
      if (isDlna) {
        playerAdapter = new UpnpPlayerAdapter(
          RadioService.this,
          RadioService.this,
          radio,
          lockKey,
          httpServer,
          chosenDevice,
          upnpActionController);
        session.setPlaybackToRemote(volumeProviderCompat);
      } else {
        playerAdapter = new LocalPlayerAdapter(
          RadioService.this,
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
      // Relaunch or play
      if (!relaunch()) {
        playerAdapter.play();
      }
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
    public void onStop() {
      playerAdapter.stop();
    }

    private Device<?, ?, ?> getChosenDevice(String identity) {
      if (androidUpnpService != null) {
        for (Device<?, ?, ?> device : androidUpnpService.getRegistry().getDevices()) {
          if (DlnaDevice.getIdentity(device).equals(identity)) {
            return device;
          }
        }
      }
      return null;
    }

    // Do nothing if no active session or no radio fund
    private void skipTo(int direction) {
      if (session.isActive()) {
        Bundle extras = mediaController.getExtras();
        Long nextRadioId = radioLibrary.get(
          Long.valueOf(getMediaId()),
          extras.getBoolean(getString(R.string.key_preferred_radios)),
          direction);
        if (nextRadioId != null) {
          // Same extras are reused
          mediaController.getTransportControls().prepareFromMediaId(nextRadioId.toString(), extras);
        }
      }
    }
  }

  // Helper class for UPnP actions scheduling
  public class UpnpActionController {
    private final Map<Radio, String> contentTypes = new Hashtable<>();
    private final Map<Device<?, ?, ?>, List<String>> protocolInfos = new Hashtable<>();
    private final List<UpnpAction> upnpActions = new Vector<>();
    private boolean isRunning = false;

    @Nullable
    public String getContentType(@NonNull Radio radio) {
      return contentTypes.get(radio);
    }

    public AndroidUpnpService getAndroidUpnpService() {
      return androidUpnpService;
    }

    @Nullable
    public List<String> getProtocolInfo(@NonNull Device<?, ?, ?> device) {
      return protocolInfos.get(device);
    }

    public void putProtocolInfo(@NonNull Device<?, ?, ?> device, @NonNull List<String> list) {
      protocolInfos.put(device, list);
    }

    public void putContentType(@NonNull Radio radio, @NonNull String contentType) {
      contentTypes.put(radio, contentType);
    }

    public void runNextAction() {
      handler.post(() -> {
        if (upnpActions.isEmpty()) {
          isRunning = false;
        } else {
          pullAction();
        }
      });
    }

    public void schedule(@NonNull final UpnpAction upnpAction) {
      handler.post(() -> {
        upnpActions.add(upnpAction);
        if (!isRunning) {
          pullAction();
        }
      });
    }

    // Remove remaining actions on device or all (device == null)
    public void releaseActions(@Nullable final Device<?, ?, ?> device) {
      handler.post(() -> {
        if (device == null) {
          upnpActions.clear();
        } else {
          Iterator<UpnpAction> iter = upnpActions.iterator();
          while (iter.hasNext()) {
            if (iter.next().getDevice().equals(device)) {
              iter.remove();
            }
          }
        }
        isRunning = false;
      });
    }

    private void release() {
      contentTypes.clear();
      protocolInfos.clear();
      upnpActions.clear();
      isRunning = false;
    }

    private void pullAction() {
      isRunning = true;
      upnpActions.remove(0).execute(this, true);
    }
  }
}