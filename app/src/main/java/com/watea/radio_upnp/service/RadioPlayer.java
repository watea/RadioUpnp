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
import com.watea.radio_upnp.model.SessionDevice;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

@OptIn(markerClass = UnstableApi.class)
public class RadioPlayer extends SimpleBasePlayer {
  private static final int DEVICE_MAX_VOLUME = 100;
  private static final int DEVICE_NOMINAL_VOLUME = 50;
  private static final int DEVICE_VOLUME_STEP = 5;
  private static final String PLAYLIST_SEPARATOR = "##";
  private static final String PLAYLIST_ITEM_SEPARATOR = "&&";
  private static final DeviceInfo DEVICE_INFO_REMOTE =
    new DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE).setMaxVolume(DEVICE_MAX_VOLUME).build();   // Device volume is relative
  private final Commands commands;
  @NonNull
  private final Listener listener;
  private final String remoteLabel;
  private int playerState = Player.STATE_IDLE;
  private boolean playWhenReady = false; // = true means "wants to play" (used by SimpleBasePlayer for isPlaying())
  @Nullable
  private PlaybackException playerError = null;
  @Nullable
  private SimpleBasePlayer.MediaItemData currentItem = null;
  private boolean isVolumeControlled = false;
  private String remoteSuffix = "";
  private int volume = DEVICE_NOMINAL_VOLUME;

  public RadioPlayer(
    @NonNull Looper looper,
    @NonNull Commands commands,
    @NonNull Listener listener,
    @NonNull String remoteLabel) {
    super(looper);
    this.commands = commands;
    this.listener = listener;
    this.remoteLabel = remoteLabel;
  }

  @NonNull
  private static String addPlaylistItem(@NonNull String playlist, @NonNull String item) {
    if (playlist.endsWith(item)) {
      return playlist;
    }
    final DateFormat dateFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    final String entry = dateFormat.format(Calendar.getInstance().getTime()) + PLAYLIST_ITEM_SEPARATOR + item;
    return playlist.isEmpty() ? entry : playlist + PLAYLIST_SEPARATOR + entry;
  }

  // Must be called at init
  public void init(boolean isVolumeControlled, @NonNull Radio radio, @NonNull String playlist) {
    this.isVolumeControlled = isVolumeControlled;
    remoteSuffix = isVolumeControlled ? " " + remoteLabel : "";
    volume = DEVICE_NOMINAL_VOLUME;
    buildSessionMetadata(radio, "", playlist);
    setState(SessionDevice.State.BUFFERING);
  }

  @NonNull
  public String getCurrentPlaylist() {
    final MediaItem item = getCurrentMediaItem();
    if ((item == null) || (item.mediaMetadata.extras == null)) {
      return "";
    }
    final String playlist = item.mediaMetadata.extras.getString(RadioService.PLAYLIST);
    return (playlist == null) ? "" : playlist;
  }

  public void buildSessionMetadata(@NonNull Radio radio, @NonNull String information) {
    buildSessionMetadata(radio, information, addPlaylistItem(getCurrentPlaylist(), information));
  }

  @NonNull
  public SessionDevice.State getSessionDeviceState() {
    switch (playerState) {
      case Player.STATE_READY:
        return playWhenReady ? SessionDevice.State.PLAYING : SessionDevice.State.PAUSED;
      case Player.STATE_BUFFERING:
        return SessionDevice.State.BUFFERING;
      default:
        return (playerError != null) ? SessionDevice.State.ERROR : SessionDevice.State.IDLE;
    }
  }

  @NonNull
  @Override
  protected SimpleBasePlayer.State getState() {
    final SimpleBasePlayer.State.Builder builder = new SimpleBasePlayer.State.Builder()
      .setPlaybackState(playerState)
      .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
      .setPlayerError(playerError)
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
  public void setState(@NonNull SessionDevice.State internalState) {
    playWhenReady = false;
    playerError = null;
    switch (internalState) {
      case PLAYING:
        playerState = Player.STATE_READY;
        playWhenReady = true;
        listener.onStatePlaying();
        break;
      case BUFFERING:
        playerState = Player.STATE_BUFFERING;
        playWhenReady = true;
        listener.onStateBuffering();
        break;
      case PAUSED:
        playerState = Player.STATE_READY;
        listener.onStatePaused();
        break;
      case ERROR:
        playerState = Player.STATE_IDLE;
        playerError = new PlaybackException(null, null, PlaybackException.ERROR_CODE_UNSPECIFIED);
        listener.onStateError();
        break;
      default:
        playerState = Player.STATE_IDLE;
        listener.onStateIdle();
    }
    invalidateState();
  }

  @NonNull
  @Override
  protected ListenableFuture<?> handleSetPlayWhenReady(boolean playWhenReadyParam) {
    if (playWhenReadyParam) {
      if (!playWhenReady) {
        commands.onPlay();
      }
    } else {
      commands.onPause();
    }
    return Futures.immediateVoidFuture();
  }

  @NonNull
  @Override
  protected ListenableFuture<?> handleStop() {
    commands.onStop();
    return Futures.immediateVoidFuture();
  }

  @NonNull
  @Override
  protected ListenableFuture<?> handleSeek(int mediaItemIndex, long positionMs, @Player.Command int seekCommand) {
    if ((seekCommand == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM) || (seekCommand == Player.COMMAND_SEEK_TO_NEXT)) {
      commands.onSkipToNext();
    } else if ((seekCommand == Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM) || (seekCommand == Player.COMMAND_SEEK_TO_PREVIOUS)) {
      commands.onSkipToPrevious();
    }
    return Futures.immediateVoidFuture();
  }

  @NonNull
  @Override
  protected ListenableFuture<?> handleSetDeviceVolume(int newDeviceVolume, @C.VolumeFlags int flags) {
    final int direction = Integer.compare(newDeviceVolume, volume);
    volume = Math.max(0, Math.min(DEVICE_MAX_VOLUME, newDeviceVolume));
    invalidateState();
    if (direction != 0) {
      commands.onAdjustVolume((direction > 0) ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER);
    }
    return Futures.immediateVoidFuture();
  }

  @NonNull
  @Override
  protected ListenableFuture<?> handleIncreaseDeviceVolume(@C.VolumeFlags int flags) {
    volume = Math.min(DEVICE_MAX_VOLUME, volume + DEVICE_VOLUME_STEP);
    invalidateState();
    commands.onAdjustVolume(AudioManager.ADJUST_RAISE);
    return Futures.immediateVoidFuture();
  }

  @NonNull
  @Override
  protected ListenableFuture<?> handleDecreaseDeviceVolume(@C.VolumeFlags int flags) {
    volume = Math.max(0, volume - DEVICE_VOLUME_STEP);
    invalidateState();
    commands.onAdjustVolume(AudioManager.ADJUST_LOWER);
    return Futures.immediateVoidFuture();
  }

  private void buildSessionMetadata(@NonNull Radio radio, @NonNull String information, @NonNull String playlist) {
    final Bundle extras = new Bundle();
    extras.putString(RadioService.PLAYLIST, playlist);
    setCurrentItem(radio.getId(),
      radio.getMediaMetadataBuilder(remoteSuffix, information)
        .setExtras(extras)
        .build());
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
        COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
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

  public interface Listener {
    default void onStatePlaying() {
    }

    default void onStateBuffering() {
    }

    default void onStatePaused() {
    }

    default void onStateIdle() {
    }

    default void onStateError() {
    }
  }
}