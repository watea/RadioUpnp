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

import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.LibraryResult;
import androidx.media3.session.MediaLibraryService;
import androidx.media3.session.MediaLibraryService.MediaLibrarySession;
import androidx.media3.session.MediaSession;
import androidx.media3.session.SessionCommand;
import androidx.media3.session.SessionCommands;
import androidx.media3.session.SessionError;
import androidx.media3.session.SessionResult;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.AsyncCallable;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import com.watea.radio_upnp.R;
import com.watea.radio_upnp.model.Radio;
import com.watea.radio_upnp.model.Radios;

import java.util.Collections;
import java.util.List;

// RadioService session callback: library browsing, item resolution and custom commands
class MediaLibrarySessionCallback implements MediaLibrarySession.Callback {
  private static final String LOG_TAG = MediaLibrarySessionCallback.class.getSimpleName();
  private static final String MEDIA_ROOT_ID = "root_id";
  private static final String RECENT_ROOT_ID = "recent_root_id"; // Playback resumption: last played radio
  private final Context context;
  private final SleepController sleepController;
  private final SettableFuture<Void> radiosInitFuture = SettableFuture.create(); // Completed at radios init; browse requests received before wait on it

  MediaLibrarySessionCallback(@NonNull Context context, @NonNull SleepController sleepController) {
    this.context = context;
    this.sleepController = sleepController;
  }

  @NonNull
  private static MediaItem toMediaItem(@NonNull Radio radio) {
    return new MediaItem.Builder()
      .setMediaId(radio.getId())
      .setMediaMetadata(radio.getMediaMetadataBuilder("", "")
        .setIsPlayable(true)
        .setIsBrowsable(false)
        .build())
      .build();
  }

  // Empty search query matches first radio, as every name contains ""
  @Nullable
  private static Radio getRadio(@NonNull MediaItem mediaItem) {
    final String query = mediaItem.requestMetadata.searchQuery;
    return (mediaItem.mediaId.isEmpty() && (query != null)) ?
      Radios.getInstance().getRadioFromName(query) :
      Radios.getInstance().getRadioFromId(mediaItem.mediaId);
  }

  @NonNull
  private static LibraryResult<MediaItem> getItem(@NonNull String mediaId) {
    final Radio radio = Radios.getInstance().getRadioFromId(mediaId);
    return (radio == null) ?
      LibraryResult.ofError(SessionError.ERROR_BAD_VALUE) :
      LibraryResult.ofItem(toMediaItem(radio), null);
  }

  private static boolean isRootId(@NonNull String mediaId) {
    return MEDIA_ROOT_ID.equals(mediaId) || RECENT_ROOT_ID.equals(mediaId);
  }

  @NonNull
  private static MediaItem getRootItem(@NonNull String rootId) {
    return new MediaItem.Builder()
      .setMediaId(rootId)
      .setMediaMetadata(new MediaMetadata.Builder()
        .setIsBrowsable(true)
        .setIsPlayable(false)
        .build())
      .build();
  }

  @OptIn(markerClass = UnstableApi.class)
  @Override
  @NonNull
  public MediaSession.ConnectionResult onConnect(@NonNull MediaSession session, @NonNull MediaSession.ControllerInfo controller) {
    final SessionCommands sessionCommands = new SessionCommands.Builder()
      .add(SessionCommand.COMMAND_CODE_LIBRARY_GET_LIBRARY_ROOT)
      .add(SessionCommand.COMMAND_CODE_LIBRARY_GET_CHILDREN)
      .add(SessionCommand.COMMAND_CODE_LIBRARY_GET_ITEM)
      .add(SessionCommand.COMMAND_CODE_LIBRARY_SUBSCRIBE)
      .add(SessionCommand.COMMAND_CODE_LIBRARY_UNSUBSCRIBE)
      .add(new SessionCommand(RadioService.ACTION_SLEEP_SET, Bundle.EMPTY))
      .add(new SessionCommand(RadioService.ACTION_SLEEP_CANCEL, Bundle.EMPTY))
      .build();
    return MediaSession.ConnectionResult.accept(sessionCommands, new Player.Commands.Builder().addAllCommands().build());
  }

  @Override
  @NonNull
  public ListenableFuture<LibraryResult<MediaItem>> onGetLibraryRoot(
    @NonNull MediaLibrarySession session,
    @NonNull MediaSession.ControllerInfo browser,
    @Nullable MediaLibraryService.LibraryParams params) {
    Log.d(LOG_TAG, "onGetLibraryRoot");
    final boolean isRecent = (params != null) && params.isRecent;
    return Futures.immediateFuture(LibraryResult.ofItem(getRootItem(isRecent ? RECENT_ROOT_ID : MEDIA_ROOT_ID), params));
  }

  @Override
  @NonNull
  public ListenableFuture<LibraryResult<MediaItem>> onGetItem(
    @NonNull MediaLibrarySession session,
    @NonNull MediaSession.ControllerInfo browser,
    @NonNull String mediaId) {
    Log.d(LOG_TAG, "onGetItem: " + mediaId);
    // Roots are needed at once: default onSubscribe accepts subscription on browsable result
    if (isRootId(mediaId)) {
      return Futures.immediateFuture(LibraryResult.ofItem(getRootItem(mediaId), null));
    }
    return whenRadiosInit(() -> Futures.immediateFuture(getItem(mediaId)));
  }

  @Override
  @NonNull
  public ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> onGetChildren(
    @NonNull MediaLibrarySession session,
    @NonNull MediaSession.ControllerInfo browser,
    @NonNull String parentId,
    int page,
    int pageSize,
    @Nullable MediaLibraryService.LibraryParams params) {
    Log.d(LOG_TAG, "onGetChildren: " + parentId);
    if (!isRootId(parentId)) {
      return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE, params));
    }
    // An empty answer before radios init would stick
    return whenRadiosInit(() -> Futures.immediateFuture(getChildren(parentId, page, pageSize, params)));
  }

  @Override
  @NonNull
  public ListenableFuture<SessionResult> onCustomCommand(
    @NonNull MediaSession session,
    @NonNull MediaSession.ControllerInfo controller,
    @NonNull SessionCommand customCommand,
    @NonNull Bundle args) {
    switch (customCommand.customAction) {
      case RadioService.ACTION_SLEEP_SET:
        sleepController.set(args.getInt(context.getString(R.string.key_sleep)));
        break;
      case RadioService.ACTION_SLEEP_CANCEL:
        sleepController.release();
        break;
      default:
        Log.e(LOG_TAG, "onCustomCommand: unknown action: " + customCommand.customAction);
    }
    return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_SUCCESS));
  }

  @Override
  @NonNull
  public ListenableFuture<List<MediaItem>> onAddMediaItems(
    @NonNull MediaSession mediaSession,
    @NonNull MediaSession.ControllerInfo controller,
    @NonNull List<MediaItem> mediaItems) {
    return whenRadiosInit(() -> resolveMediaItems(mediaItems));
  }

  @OptIn(markerClass = UnstableApi.class)
  @Override
  @NonNull
  public ListenableFuture<MediaSession.MediaItemsWithStartPosition> onPlaybackResumption(
    @NonNull MediaSession mediaSession,
    @NonNull MediaSession.ControllerInfo controller,
    boolean isForPlayback) {
    return whenRadiosInit(this::getResumptionItems);
  }

  // Must be called once radios are initialized
  void onRadiosInit() {
    radiosInitFuture.set(null);
  }

  void notifyChildrenChanged(@NonNull MediaLibrarySession session) {
    if (Radios.isInit()) {
      session.notifyChildrenChanged(MEDIA_ROOT_ID, Radios.getInstance().getActuallySelectedRadios().size(), null);
    }
  }

  // Last played radio, or first one if none; null if no radio
  @Nullable
  Radio getLastRadio() {
    final Radios radios = Radios.getInstance();
    final Radio radio = radios.getLastPlayedRadio(context);
    return ((radio == null) && !radios.isEmpty()) ? radios.get(0) : radio;
  }

  // Requests may come at service start, before radios init: answered once done, at once if already done
  @NonNull
  private <T> ListenableFuture<T> whenRadiosInit(@NonNull AsyncCallable<T> callable) {
    return Futures.transformAsync(radiosInitFuture, unused -> callable.call(), MoreExecutors.directExecutor());
  }

  @NonNull
  private LibraryResult<ImmutableList<MediaItem>> getChildren(
    @NonNull String parentId,
    int page,
    int pageSize,
    @Nullable MediaLibraryService.LibraryParams params) {
    final List<Radio> radios;
    if (RECENT_ROOT_ID.equals(parentId)) {
      final Radio lastRadio = getLastRadio();
      radios = (lastRadio == null) ? Collections.emptyList() : Collections.singletonList(lastRadio);
    } else {
      radios = Radios.getInstance().getActuallySelectedRadios();
    }
    // Requested page, empty if out of range; long arithmetic as pageSize may be Integer.MAX_VALUE
    final int from = (int) Math.min((long) page * pageSize, radios.size());
    final int to = (int) Math.min((long) from + pageSize, radios.size());
    final ImmutableList.Builder<MediaItem> items = new ImmutableList.Builder<>();
    for (final Radio radio : radios.subList(from, to)) {
      items.add(toMediaItem(radio));
    }
    return LibraryResult.ofItemList(items.build(), params);
  }

  // Single item player: first resolved item only.
  // Fails if none is found, so that playback is not started.
  @NonNull
  private ListenableFuture<List<MediaItem>> resolveMediaItems(@NonNull List<MediaItem> mediaItems) {
    for (final MediaItem mediaItem : mediaItems) {
      final Radio radio = getRadio(mediaItem);
      if (radio != null) {
        return Futures.immediateFuture(ImmutableList.of(toMediaItem(radio)));
      }
    }
    Log.w(LOG_TAG, "resolveMediaItems: no radio found");
    return Futures.immediateFailedFuture(new IllegalArgumentException("No radio found"));
  }

  @OptIn(markerClass = UnstableApi.class)
  @NonNull
  private ListenableFuture<MediaSession.MediaItemsWithStartPosition> getResumptionItems() {
    final Radio radio = getLastRadio();
    return (radio == null) ?
      Futures.immediateFailedFuture(new IllegalStateException("No radio to resume")) :
      Futures.immediateFuture(new MediaSession.MediaItemsWithStartPosition(ImmutableList.of(toMediaItem(radio)), 0, C.TIME_UNSET));
  }
}