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
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.car.app.connection.CarConnection;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.CommandButton;
import androidx.media3.session.LibraryResult;
import androidx.media3.session.MediaLibraryService;
import androidx.media3.session.MediaNotification;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaStyleNotificationHelper.MediaStyle;
import androidx.media3.session.SessionCommand;
import androidx.media3.session.SessionCommands;
import androidx.media3.session.SessionResult;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.watea.radio_upnp.R;
import com.watea.radio_upnp.activity.MainActivity;
import com.watea.radio_upnp.cast.CastManager;
import com.watea.radio_upnp.model.Radio;
import com.watea.radio_upnp.model.Radios;
import com.watea.radio_upnp.service.SessionDevice.State;
import com.watea.radio_upnp.upnp.Device;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@OptIn(markerClass = UnstableApi.class)
public class RadioService
  extends MediaLibraryService
  implements
  SessionDevice.Listener,
  CastManager.Callback,
  RadioPlayer.Commands,
  MediaNotification.Provider,
  MediaLibraryService.MediaLibrarySession.Callback {
  public static final boolean KEY_PCM_MODE_DEFAULT = true;
  public static final String ACTION_SLEEP_SET = "ACTION_SLEEP_SET";
  public static final String ACTION_SLEEP_CANCEL = "ACTION_SLEEP_CANCEL";
  // Media action intent strings (for notification PendingIntents → onStartCommand)
  private static final String ACTION_MEDIA_PLAY = "ACTION_MEDIA_PLAY";
  private static final String ACTION_MEDIA_PAUSE = "ACTION_MEDIA_PAUSE";
  private static final String ACTION_MEDIA_STOP = "ACTION_MEDIA_STOP";
  private static final String ACTION_MEDIA_SKIP_NEXT = "ACTION_MEDIA_SKIP_NEXT";
  private static final String ACTION_MEDIA_SKIP_PREVIOUS = "ACTION_MEDIA_SKIP_PREVIOUS";
  private static final String LOG_TAG = RadioService.class.getSimpleName();
  private static final int REQUEST_CODE = 501;
  private static final String MEDIA_ROOT_ID = "root_id";
  private static final int FOREGROUND_NOTIFICATION_ID = 9;
  private static final Handler HANDLER = new Handler(Looper.getMainLooper());
  private static String CHANNEL_ID;
  private final SleepController sleepController = new SleepController();
  private boolean isAndroidAutoConnected = false;
  private final Observer<Integer> carConnectionObserver = type -> {
    final boolean connected = (type != null) && (type != CarConnection.CONNECTION_TYPE_NOT_CONNECTED);
    isAndroidAutoConnected = connected;
    Log.d(LOG_TAG, "Android Auto: " + (connected ? "CONNECTED" : "DISCONNECTED"));
  };
  private boolean isLastRadioToLaunch = false;
  @Nullable
  private String pendingSearchQuery = null;
  @Nullable
  private SessionDevice sessionDevice = null;
  @Nullable
  private AndroidUpnpService.UpnpService upnpService = null;
  private final ServiceConnection upnpConnection = new ServiceConnection() {
    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
      upnpService = (AndroidUpnpService.UpnpService) service;
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
      upnpService = null;
    }
  };
  private NotificationManagerCompat notificationManager;
  private MediaLibrarySession mediaLibrarySession;
  private RadioPlayer radioPlayer;
  @Nullable
  private StreamServer streamServer = null;
  private NotificationCompat.Action actionPause;
  private NotificationCompat.Action actionPlay;
  private NotificationCompat.Action actionSkipToNext;
  private NotificationCompat.Action actionSkipToPrevious;
  @Nullable
  private LiveData<Integer> carConnectionLiveData = null;
  private CastManager castManager;
  private final Radios.Listener radiosListener = new Radios.Listener() {
    @Override
    public void onPreferredChange() {
      notifyChildrenChanged();
    }

    @Override
    public void onAdd(@NonNull Radio radio) {
      notifyChildrenChanged();
    }

    @Override
    public void onAddAll(@NonNull Collection<? extends Radio> c) {
      notifyChildrenChanged();
    }

    @Override
    public void onChange(@NonNull Radio radio) {
      notifyChildrenChanged();
    }

    @Override
    public void onRemove(int index) {
      notifyChildrenChanged();
    }

    @Override
    public void onInitEnd() {
      Log.d(LOG_TAG, "onInitEnd");
      HANDLER.post(() -> {
        mediaLibrarySession.notifyChildrenChanged(MEDIA_ROOT_ID, 0, null);
        if (pendingSearchQuery != null) {
          playFromSearch(pendingSearchQuery);
          pendingSearchQuery = null;
        } else if (isLastRadioToLaunch) {
          launchLastRadio();
          isLastRadioToLaunch = false;
        }
      });
    }

    private void notifyChildrenChanged() {
      if (Radios.isInit()) {
        mediaLibrarySession.notifyChildrenChanged(MEDIA_ROOT_ID, 0, null);
      }
    }
  };

  @NonNull
  private static SharedPreferences getAppPreferences(@NonNull Context context) {
    return context.getSharedPreferences("activity.MainActivity", Context.MODE_PRIVATE);
  }

  @Override
  public void onCreate() {
    super.onCreate();
    Log.d(LOG_TAG, "onCreate");
    // Create RadioPlayer and MediaLibrarySession
    radioPlayer = new RadioPlayer(this, getString(R.string.remote));
    mediaLibrarySession = new MediaLibraryService.MediaLibrarySession.Builder(this, radioPlayer, this)
      .setSessionActivity(PendingIntent.getActivity(
        this, REQUEST_CODE,
        new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE))
      .build();
    setMediaNotificationProvider(this);
    // Notification
    CHANNEL_ID = getResources().getString(R.string.app_name) + "." + LOG_TAG;
    notificationManager = NotificationManagerCompat.from(this);
    // Cancel all notifications to handle the case where the Service was killed and
    // restarted by the system
    notificationManager.cancelAll();
    // Create the (mandatory) notification channel
    if (notificationManager.getNotificationChannel(CHANNEL_ID) == null) {
      final NotificationChannel notificationChannel = new NotificationChannel(
        CHANNEL_ID,
        getString(R.string.radio_service_notification_name),
        NotificationManager.IMPORTANCE_HIGH);
      // Configure the notification channel
      notificationChannel.setDescription(getString(R.string.radio_service_description)); // User-visible
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
    // Bind to UPnP service
    if (!bindService(new Intent(this, AndroidUpnpService.class), upnpConnection, BIND_AUTO_CREATE)) {
      Log.e(LOG_TAG, "Internal failure; AndroidUpnpService not bound");
    }
    // Android Auto detection
    carConnectionLiveData = new CarConnection(this).getType();
    carConnectionLiveData.observeForever(carConnectionObserver);
    // Cast
    castManager = CastManager.getInstance();
    castManager.setContext(this, this);
    // Create radios if needed
    Radios.setInstance(this, null);
    Radios.getInstance().addListener(radiosListener);
    // Launch HTTP server
    try {
      streamServer = new StreamServer(this);
      streamServer.start();
    } catch (IOException iOException) {
      Log.e(LOG_TAG, "HTTP server creation failed", iOException);
      streamServer = null;
    }
    // Prepare notification actions using service intents
    actionPause = new NotificationCompat.Action(
      R.drawable.ic_pause_white_24dp,
      getString(R.string.action_pause),
      buildServicePendingIntent(ACTION_MEDIA_PAUSE));
    actionPlay = new NotificationCompat.Action(
      R.drawable.ic_play_arrow_white_24dp,
      getString(R.string.action_play),
      buildServicePendingIntent(ACTION_MEDIA_PLAY));
    actionSkipToNext = new NotificationCompat.Action(
      R.drawable.ic_skip_next_white_24dp,
      getString(R.string.action_skip_to_next),
      buildServicePendingIntent(ACTION_MEDIA_SKIP_NEXT));
    actionSkipToPrevious = new NotificationCompat.Action(
      R.drawable.ic_skip_previous_white_24dp,
      getString(R.string.action_skip_to_previous),
      buildServicePendingIntent(ACTION_MEDIA_SKIP_PREVIOUS));
  }

  @Override
  public void onDestroy() {
    Log.d(LOG_TAG, "onDestroy");
    // Stop and null sessionDevice before super.onDestroy() so onUpdateNotification sees null and removes the notification
    if (sessionDevice != null) {
      sessionDevice.stop();
      sessionDevice = null;
    }
    super.onDestroy();
    // Radios
    Radios.getInstance().removeListener(radiosListener);
    // Release HTTP server
    if (streamServer != null) {
      try {
        streamServer.stop();
      } catch (IOException ioException) {
        Log.d(LOG_TAG, "onDestroy: unable to stop HTTP server", ioException);
      }
    }
    // Release UPnP service
    unbindService(upnpConnection);
    // Android Auto detection
    if (carConnectionLiveData != null) {
      carConnectionLiveData.removeObserver(carConnectionObserver);
    }
    // Release Cast
    castManager.resetContext(this);
    // Finally session
    mediaLibrarySession.release();
  }

  @Nullable
  @Override
  public MediaLibrarySession onGetSession(@NonNull MediaSession.ControllerInfo controllerInfo) {
    return mediaLibrarySession;
  }

  @Override
  public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
    if ((intent != null) && (intent.getAction() != null)) {
      switch (intent.getAction()) {
        case ACTION_MEDIA_PLAY:
          onPlay();
          break;
        case ACTION_MEDIA_PAUSE:
          onPause();
          break;
        case ACTION_MEDIA_STOP:
          onStop();
          break;
        case ACTION_MEDIA_SKIP_NEXT:
          onSkipToNext();
          break;
        case ACTION_MEDIA_SKIP_PREVIOUS:
          onSkipToPrevious();
          break;
        case ACTION_SLEEP_CANCEL:
          sleepController.release();
          break;
      }
    }
    return super.onStartCommand(intent, flags, startId);
  }

  @Override
  public void onUpdateNotification(@NonNull MediaSession session, boolean startInForegroundRequired) {
    if (startInForegroundRequired) {
      // startForeground must always be called when required to avoid RemoteServiceException
      final Notification notification = getNotification();
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        startForeground(
          FOREGROUND_NOTIFICATION_ID,
          notification,
          (sessionDevice != null) && sessionDevice.isRemote()
            ? ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            : ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
      } else {
        startForeground(FOREGROUND_NOTIFICATION_ID, notification);
      }
      if (sessionDevice == null) {
        stopForeground(STOP_FOREGROUND_REMOVE);
      }
    } else if (sessionDevice == null) {
      stopForeground(STOP_FOREGROUND_REMOVE);
    } else {
      try {
        notificationManager.notify(FOREGROUND_NOTIFICATION_ID, getNotification());
      } catch (SecurityException securityException) {
        Log.e(LOG_TAG, "Internal failure; notification not allowed");
      }
    }
  }

  @Override
  public void onTaskRemoved(@Nullable Intent rootIntent) {
    super.onTaskRemoved(rootIntent);
    stopSelf();
  }

  @Override
  public void onCastStarting() {
    Log.d(LOG_TAG, "onCastStarting");
    // UPnP no longer possible
    if (upnpService != null) {
      upnpService.setSelectedDeviceIdentity(null);
    }
  }

  @Override
  public void onCastStarted() {
    final State state = getPlaybackState();
    Log.d(LOG_TAG, "onCastStarted with state: " + state);
    if ((sessionDevice != null) && ((state == State.BUFFERING) || (state == State.PAUSED) || (state == State.PLAYING))) {
      playFromMediaId(sessionDevice.getRadio().getId());
    }
  }

  @Override
  public void onCastStop() {
    Log.d(LOG_TAG, "onCastStop");
    onStop();
  }

  @Override
  public void onNewInformation(@NonNull String information, @NonNull String lockKey) {
    runIfLocked(lockKey, () -> {
      Log.d(LOG_TAG, "onNewInformation: " + information);
      assert sessionDevice != null;
      radioPlayer.buildSessionMetadata(sessionDevice.getRadio(), information);
      buildNotification();
    });
  }

  @Override
  public void onNewBitrate(int bitrate, @NonNull String mimeType, @NonNull String lockKey) {
    runIfLocked(lockKey, () -> {
      Log.d(LOG_TAG, "onNewBitrate: " + bitrate);
      final String bitrateDisplay = (bitrate > 0) ? Integer.toString(bitrate) : "--";
      final Bundle extras = new Bundle(mediaLibrarySession.getSessionExtras());
      extras.putString(getString(R.string.key_bitrate), bitrateDisplay);
      extras.putString(getString(R.string.key_mime_type), mimeType);
      mediaLibrarySession.setSessionExtras(extras);
      buildNotification();
    });
  }

  @Override
  public void onState(@NonNull State state, @NonNull String lockKey) {
    runIfLocked(lockKey, () -> {
      Log.d(LOG_TAG, "onState: " + state.name());
      if ((sessionDevice == null) || (getPlaybackState() == state)) {
        return;
      }
      radioPlayer.setState(state);
      switch (state) {
        case PLAYING:
        case BUFFERING:
          break;
        case PAUSED:
          releaseResources();
          break;
        case ERROR:
          if (sessionDevice.consumeRewind()) {
            sessionDevice.release();
            playFromMediaId(sessionDevice.getRadio().getId());
            return;
          }
          // fall through
        default:
          releaseResources();
          sessionDevice.release();
          sessionDevice = null;
          stopForeground(STOP_FOREGROUND_REMOVE);
      }
    });
  }

  @NonNull
  @Override
  public State getPlaybackState() {
    return radioPlayer.getSessionDeviceState();
  }

  @Override
  public void onPlay() {
    Log.d(LOG_TAG, "onPlay");
    if (sessionDevice == null) {
      isLastRadioToLaunch = !launchLastRadio();
    } else {
      sessionDevice.play();
    }
  }

  @Override
  public void onPause() {
    Log.d(LOG_TAG, "onPause");
    if (sessionDevice == null) {
      Log.e(LOG_TAG, "onPause: sessionDevice is null");
      return;
    }
    sessionDevice.pause();
  }

  @Override
  public void onStop() {
    Log.d(LOG_TAG, "onStop");
    if (sessionDevice == null) {
      Log.e(LOG_TAG, "onStop: sessionDevice is null");
      return;
    }
    onState(State.STOPPED, sessionDevice.getLockKey());
    sessionDevice.stop();
  }

  @Override
  public void onSkipToNext() {
    Log.d(LOG_TAG, "onSkipToNext");
    skipTo(1);
  }

  @Override
  public void onSkipToPrevious() {
    Log.d(LOG_TAG, "onSkipToPrevious");
    skipTo(-1);
  }

  @Override
  public void onAdjustVolume(int direction) {
    if (sessionDevice == null) {
      Log.e(LOG_TAG, "onAdjustVolume: sessionDevice is null");
      return;
    }
    sessionDevice.adjustVolume(direction);
  }

  @NonNull
  @Override
  public MediaNotification createNotification(
    @NonNull MediaSession mediaSession,
    @NonNull ImmutableList<CommandButton> mediaButtonPreferences,
    @NonNull MediaNotification.ActionFactory actionFactory,
    @NonNull MediaNotification.Provider.Callback onNotificationChangedCallback) {
    return new MediaNotification(FOREGROUND_NOTIFICATION_ID, getNotification());
  }

  @Override
  public boolean handleCustomCommand(
    @NonNull MediaSession session,
    @NonNull String action,
    @NonNull Bundle extras) {
    return false;
  }

  @NonNull
  @Override
  public MediaSession.ConnectionResult onConnect(@NonNull MediaSession session, @NonNull MediaSession.ControllerInfo controller) {
    final SessionCommands sessionCommands = new SessionCommands.Builder()
      .add(SessionCommand.COMMAND_CODE_LIBRARY_GET_LIBRARY_ROOT)
      .add(SessionCommand.COMMAND_CODE_LIBRARY_GET_CHILDREN)
      .add(SessionCommand.COMMAND_CODE_LIBRARY_GET_ITEM)
      .add(SessionCommand.COMMAND_CODE_LIBRARY_SUBSCRIBE)
      .add(SessionCommand.COMMAND_CODE_LIBRARY_UNSUBSCRIBE)
      .add(SessionCommand.COMMAND_CODE_LIBRARY_SEARCH)
      .add(SessionCommand.COMMAND_CODE_LIBRARY_GET_SEARCH_RESULT)
      .add(new SessionCommand(ACTION_SLEEP_SET, Bundle.EMPTY))
      .add(new SessionCommand(ACTION_SLEEP_CANCEL, Bundle.EMPTY))
      .build();
    return MediaSession.ConnectionResult.accept(sessionCommands, new Player.Commands.Builder().addAllCommands().build());
  }

  @NonNull
  @Override
  public ListenableFuture<LibraryResult<MediaItem>> onGetLibraryRoot(
    @NonNull MediaLibrarySession session,
    @NonNull MediaSession.ControllerInfo browser,
    @Nullable MediaLibraryService.LibraryParams params) {
    Log.d(LOG_TAG, "onGetLibraryRoot");
    return Futures.immediateFuture(LibraryResult.ofItem(
      new MediaItem.Builder()
        .setMediaId(MEDIA_ROOT_ID)
        .setMediaMetadata(new MediaMetadata.Builder()
          .setIsBrowsable(true)
          .setIsPlayable(false)
          .build())
        .build(), params));
  }

  @NonNull
  @Override
  public ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> onGetChildren(
    @NonNull MediaLibrarySession session,
    @NonNull MediaSession.ControllerInfo browser,
    @NonNull String parentId,
    int page,
    int pageSize,
    @Nullable MediaLibraryService.LibraryParams params) {
    Log.d(LOG_TAG, "onGetChildren: " + parentId);
    if (!MEDIA_ROOT_ID.equals(parentId) || !Radios.isInit()) {
      return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of(), params));
    }
    final ImmutableList.Builder<MediaItem> items = new ImmutableList.Builder<>();
    for (final Radio radio : Radios.getInstance().getActuallySelectedRadios()) {
      items.add(new MediaItem.Builder()
        .setMediaId(radio.getId())
        .setMediaMetadata(radio.getMediaMetadataBuilder("", "")
          .setIsPlayable(true)
          .setIsBrowsable(false)
          .build())
        .build());
    }
    return Futures.immediateFuture(LibraryResult.ofItemList(items.build(), params));
  }

  @NonNull
  @Override
  public ListenableFuture<SessionResult> onCustomCommand(
    @NonNull MediaSession session,
    @NonNull MediaSession.ControllerInfo controller,
    @NonNull SessionCommand customCommand,
    @NonNull Bundle args) {
    switch (customCommand.customAction) {
      case ACTION_SLEEP_SET:
        HANDLER.post(() -> sleepController.set(args.getInt(getString(R.string.key_sleep))));
        break;
      case ACTION_SLEEP_CANCEL:
        HANDLER.post(sleepController::release);
        break;
      default:
        Log.e(LOG_TAG, "onCustomCommand: unknown action: " + customCommand.customAction);
    }
    return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_SUCCESS));
  }

  @NonNull
  @Override
  public ListenableFuture<List<MediaItem>> onAddMediaItems(
    @NonNull MediaSession mediaSession,
    @NonNull MediaSession.ControllerInfo controller,
    @NonNull List<MediaItem> mediaItems) {
    if (!mediaItems.isEmpty()) {
      final MediaItem item = mediaItems.get(mediaItems.size() - 1);
      final String mediaId = item.mediaId;
      if (!mediaId.isEmpty() && !MEDIA_ROOT_ID.equals(mediaId)) {
        HANDLER.post(() -> playFromMediaId(mediaId));
      } else if (item.requestMetadata.searchQuery != null) {
        final String query = item.requestMetadata.searchQuery;
        HANDLER.post(() -> playFromSearch(query));
      }
    }
    return Futures.immediateFuture(mediaItems);
  }

  @NonNull
  private PendingIntent buildServicePendingIntent(@NonNull String action) {
    return PendingIntent.getForegroundService(
      this, REQUEST_CODE,
      new Intent(action, null, this, RadioService.class),
      PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  @NonNull
  private Notification getNotification() {
    final MediaStyle mediaStyle = new MediaStyle(mediaLibrarySession);
    final NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_mic_white_24dp)
      // Pending intent that is fired when user clicks on notification
      .setContentIntent(PendingIntent.getActivity(
        this, REQUEST_CODE,
        new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE))
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
      builder
        .setContentTitle(metadata.subtitle)
        .setContentText(metadata.displayTitle)
        .setSubText((sessionDevice != null) && sessionDevice.isRemote() ? getString(R.string.remote) : "");
    }
    builder.addAction(actionSkipToPrevious);
    switch (getPlaybackState()) {
      case PLAYING:
        builder
          .addAction(actionPause)
          .setOngoing(true);
        mediaStyle.setShowActionsInCompactView(0, 1, 2);
        break;
      case PAUSED:
      case ERROR:
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

  private void buildNotification() {
    try {
      notificationManager.notify(FOREGROUND_NOTIFICATION_ID, getNotification());
    } catch (SecurityException securityException) {
      Log.e(LOG_TAG, "Internal failure; notification not allowed");
    }
  }

  private void runIfLocked(@NonNull final String lockKey, @NonNull final Runnable runnable) {
    HANDLER.post(() -> {
      if ((sessionDevice != null) && lockKey.equals(sessionDevice.getLockKey())) {
        runnable.run();
      }
    });
  }

  private void releaseResources() {
    if (streamServer != null) {
      streamServer.release();
    }
    sleepController.release();
  }

  private void playFromMediaId(@NonNull String mediaId) {
    Log.d(LOG_TAG, "playFromMediaId with mediaId: " + mediaId);
    final Radio radio = Radios.getInstance().getRadioFromId(mediaId);
    if (radio == null) {
      Log.e(LOG_TAG, "playFromMediaId: radio not found");
      return;
    }
    Log.d(LOG_TAG, "playFromMediaId with radio: " + radio.getName() + " => " + radio.getUri());
    getAppPreferences(this).edit().putString(getString(R.string.key_last_played_radio), radio.getId()).apply();
    final Radio lastRadio = (sessionDevice == null) ? null : sessionDevice.getRadio();
    if (sessionDevice != null) {
      sessionDevice.release();
    }
    sleepController.release();
    sessionDevice = createSessionDevice(radio);
    mediaLibrarySession.setSessionExtras(new Bundle());
    radioPlayer.init(radio, sessionDevice.isRemote(), (radio == lastRadio));
    sessionDevice.launch();
    startForegroundService(new Intent(this, RadioService.class));
  }

  private void playFromSearch(@Nullable String query) {
    Log.d(LOG_TAG, "playFromSearch: query = " + query);
    if (!Radios.isInit()) {
      pendingSearchQuery = query;
      return;
    }
    final Radios radios = Radios.getInstance();
    Radio match = null;
    if ((query == null) || query.trim().isEmpty()) {
      if (!radios.isEmpty()) {
        match = radios.get(0);
      }
    } else {
      final String normalizedQuery = query.trim().toLowerCase(Locale.getDefault());
      match = radios.stream().filter(radio -> radio.getName().toLowerCase(Locale.getDefault()).equals(normalizedQuery)).findAny().orElse(null);
      if (match == null) {
        match = radios.stream().filter(radio -> radio.getName().toLowerCase(Locale.getDefault()).contains(normalizedQuery)).findAny().orElse(null);
      }
    }
    if (match == null) {
      Log.w(LOG_TAG, "playFromSearch: no match found for query = " + query);
    } else {
      Log.d(LOG_TAG, "playFromSearch: matched radio = " + match.getName());
      playFromMediaId(match.getId());
    }
  }

  private void skipTo(int direction) {
    if (sessionDevice == null) {
      Log.e(LOG_TAG, "skipTo: sessionDevice is null");
      return;
    }
    final Radio nextRadio = Radios.getInstance().getRadioFrom(sessionDevice.getRadio(), direction);
    if (nextRadio == null) {
      Log.e(LOG_TAG, "skipTo: next radio is null");
      return;
    }
    playFromMediaId(nextRadio.getId());
  }

  // true if work done
  private boolean launchLastRadio() {
    Log.d(LOG_TAG, "launchLastRadio");
    if (Radios.isInit()) {
      final Radios radios = Radios.getInstance();
      if (!radios.isEmpty()) {
        playFromMediaId(getAppPreferences(this).getString(getString(R.string.key_last_played_radio), radios.get(0).getId()));
      }
      return true;
    }
    return false;
  }

  // UPnP or Cast not accepted if environment not OK: force local processing
  @NonNull
  private SessionDevice createSessionDevice(@NonNull Radio radio) {
    SessionDevice result = null;
    if (!isAndroidAutoConnected && (streamServer != null) && new NetworkProxy(this).isOnWifi()) {
      final Device upnpSelectedDevice = (upnpService == null) ? null : upnpService.getActiveSelectedDevice();
      final Consumer<Radio> onPlayCallback = currentRadio -> playFromMediaId(currentRadio.getId());
      if (castManager.hasCastSession()) {
        result = castManager.getCastSessionDevice(
          this,
          this,
          radio,
          onPlayCallback,
          streamServer);
      } else if (upnpSelectedDevice != null) {
        result = new UpnpSessionDevice(
          this,
          getAppPreferences(this).getBoolean(getString(R.string.key_pcm_mode), KEY_PCM_MODE_DEFAULT),
          this,
          radio,
          onPlayCallback,
          streamServer,
          upnpService.getRequestController(),
          upnpSelectedDevice);
      }
    }
    if (result == null) {
      result = new LocalSessionDevice(
        this,
        this,
        radio);
    }
    Log.d(LOG_TAG, "createSessionDevice: " + result.getClass().getSimpleName() + " - " + result.getLockKey());
    return result;
  }

  private class SleepController {
    private static final int NOTIFICATION_ID = 42;
    @Nullable
    private ScheduledExecutorService scheduler = null;

    public void set(int minutes) {
      scheduler = Executors.newScheduledThreadPool(1);
      scheduler.schedule(() -> HANDLER.post(RadioService.this::onPause), minutes, TimeUnit.MINUTES);
      scheduler.shutdown();
      setSleepOn(true);
      try {
        notificationManager.notify(NOTIFICATION_ID, new NotificationCompat.Builder(RadioService.this, CHANNEL_ID)
          .setSmallIcon(R.drawable.ic_mic_white_24dp)
          .setContentTitle(getString(R.string.sleep_timer_title))
          .setContentText(getString(R.string.sleep_timer_set_for, minutes))
          .setPriority(NotificationCompat.PRIORITY_DEFAULT)
          .setOngoing(true)
          .setOnlyAlertOnce(true)
          .addAction(new NotificationCompat.Action(
            R.drawable.ic_stop_white_24dp,
            getString(R.string.cancel_sleep_timer),
            buildServicePendingIntent(ACTION_SLEEP_CANCEL)))
          .build());
      } catch (SecurityException securityException) {
        Log.e(LOG_TAG, "Permission denied to post sleep timer notification", securityException);
      }
    }

    public void release() {
      if (scheduler != null) {
        scheduler.shutdownNow();
        scheduler = null;
        setSleepOn(false);
        notificationManager.cancel(NOTIFICATION_ID);
      }
    }

    private void setSleepOn(boolean isSleepOn) {
      final Bundle extras = new Bundle(mediaLibrarySession.getSessionExtras());
      extras.putBoolean(getString(R.string.key_sleep_set), isSleepOn);
      mediaLibrarySession.setSessionExtras(extras);
    }
  }
}