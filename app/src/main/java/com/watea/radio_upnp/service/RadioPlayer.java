/*
 * Copyright (c) 2026. Stephane Treuchot
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

import android.media.AudioManager;
import android.os.Bundle;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.DeviceInfo;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.SimpleBasePlayer;
import androidx.media3.common.util.UnstableApi;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.watea.radio_upnp.model.Radio;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@OptIn(markerClass = UnstableApi.class)
public class RadioPlayer extends SimpleBasePlayer {
  public static final String DATE = "date";
  public static final String INFORMATION = "information";
  public static final String PLAYLIST = "playlist";
  private static final String PLAYLIST_SEPARATOR = "##";
  private static final String PLAYLIST_ITEM_SEPARATOR = "&&";
  private static final int DEVICE_MAX_VOLUME = 100;
  private static final int DEVICE_NOMINAL_VOLUME = 50;
  private static final int DEVICE_VOLUME_STEP = 5;
  private static final DeviceInfo DEVICE_INFO_REMOTE =
    new DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE).setMaxVolume(DEVICE_MAX_VOLUME).build(); // Device volume is relative
  private static final PlaybackException PLAYBACK_EXCEPTION = new PlaybackException(null, null, PlaybackException.ERROR_CODE_UNSPECIFIED);
  @NonNull
  private final Commands commands;
  @NonNull
  private final String remoteLabel;
  @NonNull
  private SessionDevice.State sessionDeviceState = SessionDevice.State.IDLE;
  @Nullable
  private SimpleBasePlayer.MediaItemData currentItem = null;
  private boolean isVolumeControlled = false;
  @NonNull
  private String remoteSuffix = "";
  private int volume = DEVICE_NOMINAL_VOLUME;

  public RadioPlayer(@NonNull Commands commands, @NonNull String remoteLabel) {
    super(Looper.getMainLooper());
    this.commands = commands;
    this.remoteLabel = remoteLabel;
  }

  @NonNull
  public static List<Map<String, String>> getPlaylist(@NonNull String playlist) {
    final List<Map<String, String>> result = new ArrayList<>();
    for (final String line : playlist.split(PLAYLIST_SEPARATOR)) {
      final String[] items = line.split(PLAYLIST_ITEM_SEPARATOR);
      final Map<String, String> map = new HashMap<>();
      if (items.length == 2) {
        map.put(DATE, items[0]);
        map.put(INFORMATION, items[1]);
        result.add(map);
      }
    }
    return result;
  }

  // Must be called at init
  public void init(@NonNull Radio radio, boolean isVolumeControlled, boolean isCurrentPlaylistToKeep) {
    this.isVolumeControlled = isVolumeControlled;
    remoteSuffix = this.isVolumeControlled ? " " + remoteLabel : "";
    volume = DEVICE_NOMINAL_VOLUME;
    buildSessionMetadata(radio, "", isCurrentPlaylistToKeep ? getCurrentPlaylist() : "");
    setState(SessionDevice.State.BUFFERING);
  }

  public void buildSessionMetadata(@NonNull Radio radio, @NonNull String information) {
    String playlist = getCurrentPlaylist();
    if (!playlist.endsWith(information)) {
      final DateFormat dateFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
      final String entry = dateFormat.format(Calendar.getInstance().getTime()) + PLAYLIST_ITEM_SEPARATOR + information;
      playlist = playlist.isEmpty() ? entry : playlist + PLAYLIST_SEPARATOR + entry;
    }
    buildSessionMetadata(radio, information, playlist);
  }

  @NonNull
  public SessionDevice.State getSessionDeviceState() {
    return sessionDeviceState;
  }

  @Override
  @NonNull
  protected SimpleBasePlayer.State getState() {
    final SimpleBasePlayer.State.Builder builder = new SimpleBasePlayer.State.Builder()
      .setPlaybackState(getPlayerState())
      .setPlayWhenReady(playWhenReady(), Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
      .setPlayerError((sessionDeviceState == SessionDevice.State.ERROR) ? PLAYBACK_EXCEPTION : null)
      .setAvailableCommands(buildAvailableCommands());
    if (currentItem == null) {
      builder.setCurrentMediaItemIndex(C.INDEX_UNSET);
    } else {
      builder
        .setPlaylist(ImmutableList.of(currentItem))
        .setCurrentMediaItemIndex(0);
    }
    if (isVolumeControlled) {
      builder
        .setDeviceInfo(DEVICE_INFO_REMOTE)
        .setDeviceVolume(volume);
    }
    return builder.build();
  }

  // Must be called on main thread
  public void setState(@NonNull SessionDevice.State sessionDeviceState) {
    this.sessionDeviceState = sessionDeviceState;
    invalidateState();
  }

  @Override
  @NonNull
  protected ListenableFuture<?> handleSetPlayWhenReady(boolean playWhenReadyParam) {
    if (playWhenReadyParam) {
      if (!playWhenReady()) {
        commands.onPlay();
      }
    } else {
      commands.onPause();
    }
    return Futures.immediateVoidFuture();
  }

  @Override
  @NonNull
  protected ListenableFuture<?> handleStop() {
    commands.onStop();
    return Futures.immediateVoidFuture();
  }

  @Override
  @NonNull
  protected ListenableFuture<List<MediaItem>> handleAddMediaItems(int index, @NonNull List<MediaItem> mediaItems) {
    return Futures.immediateFuture(mediaItems);
  }

  @Override
  @NonNull
  protected ListenableFuture<?> handleSeek(int mediaItemIndex, long positionMs, @Player.Command int seekCommand) {
    if ((seekCommand == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM) || (seekCommand == Player.COMMAND_SEEK_TO_NEXT)) {
      commands.onSkipToNext();
    } else if ((seekCommand == Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM) || (seekCommand == Player.COMMAND_SEEK_TO_PREVIOUS)) {
      commands.onSkipToPrevious();
    }
    return Futures.immediateVoidFuture();
  }

  @Override
  @NonNull
  protected ListenableFuture<?> handleSetDeviceVolume(int newDeviceVolume, @C.VolumeFlags int flags) {
    final int direction = Integer.compare(newDeviceVolume, volume);
    volume = Math.max(0, Math.min(DEVICE_MAX_VOLUME, newDeviceVolume));
    invalidateState();
    if (direction != 0) {
      commands.onAdjustVolume((direction > 0) ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER);
    }
    return Futures.immediateVoidFuture();
  }

  @Override
  @NonNull
  protected ListenableFuture<?> handleIncreaseDeviceVolume(@C.VolumeFlags int flags) {
    volume = Math.min(DEVICE_MAX_VOLUME, volume + DEVICE_VOLUME_STEP);
    invalidateState();
    commands.onAdjustVolume(AudioManager.ADJUST_RAISE);
    return Futures.immediateVoidFuture();
  }

  @Override
  @NonNull
  protected ListenableFuture<?> handleDecreaseDeviceVolume(@C.VolumeFlags int flags) {
    volume = Math.max(0, volume - DEVICE_VOLUME_STEP);
    invalidateState();
    commands.onAdjustVolume(AudioManager.ADJUST_LOWER);
    return Futures.immediateVoidFuture();
  }

  private int getPlayerState() {
    switch (sessionDeviceState) {
      case PLAYING:
      case PAUSED:
        return Player.STATE_READY;
      case BUFFERING:
        return Player.STATE_BUFFERING;
      default:
        return Player.STATE_IDLE;
    }
  }

  private boolean playWhenReady() {
    return (sessionDeviceState == SessionDevice.State.PLAYING) || (sessionDeviceState == SessionDevice.State.BUFFERING);
  }

  @NonNull
  private String getCurrentPlaylist() {
    final MediaItem item = getCurrentMediaItem();
    final android.os.Bundle extras = (item == null) ? null : item.mediaMetadata.extras;
    final String playlist = (extras == null) ? null : extras.getString(PLAYLIST);
    return (playlist == null) ? "" : playlist;
  }

  private void buildSessionMetadata(@NonNull Radio radio, @NonNull String information, @NonNull String playlist) {
    final Bundle extras = new Bundle();
    extras.putString(PLAYLIST, playlist);
    setCurrentItem(radio.getId(), radio.getMediaMetadataBuilder(remoteSuffix, information).setExtras(extras).build());
  }

  private void setCurrentItem(@NonNull String mediaId, @NonNull MediaMetadata metadata) {
    currentItem = new SimpleBasePlayer.MediaItemData.Builder(mediaId)
      .setMediaItem(new MediaItem.Builder().setMediaId(mediaId).setMediaMetadata(metadata).build())
      .setMediaMetadata(metadata)
      .build();
    invalidateState();
  }

  @NonNull
  private Player.Commands buildAvailableCommands() {
    final Player.Commands.Builder builder = new Player.Commands.Builder()
      .addAll(
        COMMAND_PLAY_PAUSE,
        COMMAND_STOP,
        COMMAND_SET_MEDIA_ITEM,
        COMMAND_CHANGE_MEDIA_ITEMS,
        COMMAND_SEEK_TO_NEXT,
        COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        COMMAND_SEEK_TO_PREVIOUS,
        COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        COMMAND_GET_CURRENT_MEDIA_ITEM,
        COMMAND_GET_METADATA,
        COMMAND_GET_TIMELINE);
    if (isVolumeControlled) {
      builder.addAll(
        COMMAND_GET_DEVICE_VOLUME,
        COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
        COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS);
    }
    return builder.build();
  }

  public interface Commands {
    void onPlay();

    void onPause();

    void onStop();

    void onSkipToNext();

    void onSkipToPrevious();

    // direction is an AudioManager.ADJUST_* constant
    void onAdjustVolume(int direction);
  }
}