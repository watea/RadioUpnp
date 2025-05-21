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

package com.watea.radio_upnp.activity;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SimpleAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.watea.radio_upnp.R;
import com.watea.radio_upnp.model.Radio;
import com.watea.radio_upnp.model.Radios;
import com.watea.radio_upnp.service.RadioService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class PlayerController implements Consumer<Consumer<Radio>> {
  private static final String LOG_TAG = PlayerController.class.getSimpleName();
  @NonNull
  private final ImageButton playImageButton;
  @NonNull
  private final ImageButton preferredImageButton;
  @NonNull
  private final ProgressBar progressBar;
  @NonNull
  private final ImageView albumArtImageView;
  @NonNull
  private final LinearLayout playedRadioLinearLayout;
  @NonNull
  private final TextView playedRadioNameTextView;
  @NonNull
  private final TextView playedRadioInformationTextView;
  @NonNull
  private final TextView playedRadioRateTextView;
  @NonNull
  private final MainActivity.UserHint playLongPressUserHint;
  @NonNull
  private final MainActivity.UserHint informationPressUserHint;
  @NonNull
  private final MainActivity.UserHint informationSelectPressUserHint;
  @NonNull
  private final AlertDialog playlistAlertDialog;
  @NonNull
  private final MainActivity mainActivity;
  private final List<Map<String, String>> playInformations = new ArrayList<>();
  @NonNull
  private final MediaBrowserCompat mediaBrowser;
  @NonNull
  private final Handler longClickHandler = new Handler(Looper.getMainLooper());
  // Callback from media control
  private final MediaControllerCompatCallback mediaControllerCallback = new MediaControllerCompatCallback();
  // Callback from connection to MediaBrowserServiceCompat
  private final MediaBrowserCompatConnectionCallback mediaBrowserConnectionCallback = new MediaBrowserCompatConnectionCallback();
  // MediaController from the MediaBrowser when it has successfully connected
  @Nullable
  private MediaControllerCompat mediaController = null;
  private final Radios.Listener radiosListener = new Radios.Listener() {
    @Override
    public void onChange(@NonNull Radio radio) {
      if (radio == getCurrentRadio()) {
        setPreferredButton(radio.isPreferred());
      }
    }
  };
  @Nullable
  private Consumer<Radio> listener = null;
  private boolean isLongPress = false;

  @SuppressLint("ClickableViewAccessibility")
  public PlayerController(@NonNull MainActivity mainActivity, @NonNull View view) {
    this.mainActivity = mainActivity;
    // Build alert dialogs
    playLongPressUserHint = this.mainActivity
      .new UserHint(R.string.key_play_long_press_got_it, R.string.play_long_press);
    informationPressUserHint = this.mainActivity
      .new UserHint(R.string.key_information_press_got_it, R.string.information_press, 40);
    informationSelectPressUserHint = this.mainActivity
      .new UserHint(R.string.key_information_select_press_got_it, R.string.information_select_press, 2);
    final SimpleAdapter playlistAdapter = new SimpleAdapter(
      this.mainActivity,
      playInformations,
      R.layout.row_playlist,
      new String[]{RadioService.DATE, RadioService.INFORMATION},
      new int[]{R.id.row_playlist_date_text_view, R.id.row_playlist_information_text_view});
    playlistAlertDialog = new AlertDialog.Builder(this.mainActivity)
      .setAdapter(playlistAdapter, null)
      .create();
    playlistAlertDialog.getListView().setOnItemLongClickListener((parent, itemView, position, id) -> {
      // Concatenate all entries from the playlist
      copyToClipBoard(playInformations.stream()
        .map(item -> item.get(RadioService.INFORMATION))
        .collect(Collectors.joining("\n\n")));
      // Dismiss the dialog after handling the long press
      playlistAlertDialog.dismiss();
      // Indicate that the long press was handled
      return true;
    });
    playlistAlertDialog.getListView().setOnItemClickListener((parent, rowView, position, id) -> {
      // Get the selected item from the playlist
      final String selectedInformation = playInformations.get(position).get(RadioService.INFORMATION);
      assert selectedInformation != null;
      copyToClipBoard(selectedInformation);
      // Dismiss the dialog after handling the click
      playlistAlertDialog.dismiss();
    });
    // Create view
    albumArtImageView = view.findViewById(R.id.album_art_image_view);
    albumArtImageView.setOnClickListener(v -> {
      // Should not happen
      if (mediaController == null) {
        this.mainActivity.tell(R.string.radio_connection_waiting);
      } else {
        playInformations.clear();
        final String playInformation =
          mediaController.getMetadata().getString(RadioService.PLAYLIST);
        if (playInformation != null) {
          playInformations.addAll(RadioService.getPlaylist(playInformation));
        }
        if (playInformations.isEmpty()) {
          this.mainActivity.tell(R.string.radio_no_playlist);
        } else {
          playlistAlertDialog.show();
          informationSelectPressUserHint.show();
        }
      }
    });
    playedRadioLinearLayout = view.findViewById(R.id.played_radio_linear_layout);
    playedRadioNameTextView = view.findViewById(R.id.played_radio_name_text_view);
    playedRadioNameTextView.setSelected(true); // For scrolling
    playedRadioInformationTextView = view.findViewById(R.id.played_radio_information_text_view);
    playedRadioInformationTextView.setSelected(true); // For scrolling
    playedRadioRateTextView = view.findViewById(R.id.played_radio_rate_text_view);
    progressBar = view.findViewById(R.id.progress_bar);
    playImageButton = view.findViewById(R.id.play_image_button);
    final GestureDetector gestureDetector = new GestureDetector(this.mainActivity, new GestureDetector.SimpleOnGestureListener() {
      @Override
      public boolean onSingleTapConfirmed(@NonNull MotionEvent e) {
        if (!isLongPress) {
          onPlayClick();
        }
        return true;
      }

      // Double click
      @Override
      public boolean onDoubleTap(@NonNull MotionEvent e) {
        onPlayDoubleClick();
        return true;
      }
    });
    playImageButton.setOnTouchListener(
      (v, event) -> {
        switch (event.getAction()) {
          case MotionEvent.ACTION_DOWN:
            isLongPress = false;
            longClickHandler.postDelayed(() -> {
              isLongPress = true;
              onPlayLongClick();
            }, 500);
            break;
          case MotionEvent.ACTION_UP:
          case MotionEvent.ACTION_CANCEL:
            // No long click
            longClickHandler.removeCallbacksAndMessages(null);
            break;
        }
        return gestureDetector.onTouchEvent(event);
      });
    preferredImageButton = view.findViewById(R.id.preferred_image_button);
    preferredImageButton.setOnClickListener(v -> {
      final Radio radio = getCurrentRadio();
      if (radio == null) {
        this.mainActivity.tell(R.string.radio_not_defined);
      } else {
        Radios.getInstance().setPreferred(radio, !radio.isPreferred());
      }
    });
    // Create MediaBrowserServiceCompat
    mediaBrowser = new MediaBrowserCompat(
      this.mainActivity,
      new ComponentName(this.mainActivity, RadioService.class),
      mediaBrowserConnectionCallback,
      null);
  }

  // Must be called on activity resume
  public void onActivityCreate() {
    // Launch RadioService, may fail if already called and connection not ended
    try {
      mediaBrowser.connect();
    } catch (IllegalStateException illegalStateException) {
      Log.e(LOG_TAG, "onActivityResume: mediaBrowser.connect() failed", illegalStateException);
    }
  }

  // Must be called on activity destroy.
  // Handle services.
  public void onActivityDestroy() {
    // Disconnect mediaBrowser
    mediaBrowser.disconnect();
    // Forced suspended connection
    mediaBrowserConnectionCallback.onConnectionSuspended();
  }

  public void startReading(@NonNull Radio radio) {
    if (mediaController == null) {
      // Should not happen
      mainActivity.tell(R.string.radio_connection_waiting);
    } else {
      mediaController.getTransportControls().playFromMediaId(radio.getId(), null);
    }
  }

  public void startReading() {
    final Radio radio = getCurrentRadio();
    if (radio != null) {
      startReading(radio);
    }
  }

  @Nullable
  public Radio getCurrentRadio() {
    final MediaMetadataCompat mediaMetadataCompat = (mediaController == null) ? null : mediaController.getMetadata();
    final String radioId = (mediaMetadataCompat == null) ? null : mediaMetadataCompat.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID);
    return (radioId == null) ? null : Radios.getInstance().getRadioFromId(radioId);
  }

  private void onPlayClick() {
    if (mediaController == null) { // Should not happen
      mainActivity.tell(R.string.radio_connection_waiting);
    } else {
      // Tag on button has stored state to reach
      switch ((int) playImageButton.getTag()) {
        case PlaybackStateCompat.STATE_PLAYING:
          mediaController.getTransportControls().play();
          break;
        case PlaybackStateCompat.STATE_PAUSED:
          mediaController.getTransportControls().pause();
          playLongPressUserHint.show();
          break;
        case PlaybackStateCompat.STATE_STOPPED:
          mediaController.getTransportControls().stop();
          break;
        case PlaybackStateCompat.STATE_REWINDING:
          mediaController.getTransportControls().rewind();
          break;
        default:
          // Should not happen
          Log.e(LOG_TAG, "Internal failure, no action to perform on play button");
      }
    }
  }

  private void onPlayLongClick() {
    if (mediaController == null) { // Should not happen
      mainActivity.tell(R.string.radio_connection_waiting);
    } else {
      mediaController.getTransportControls().stop();
    }
  }

  private void onPlayDoubleClick() {
    // Should not happen
    if (mediaController == null) {
      mainActivity.tell(R.string.radio_connection_waiting);
    } else {
      final boolean isSleepSet = mediaController.getExtras().getBoolean(mainActivity.getString(R.string.key_sleep_set), false);
      final String key = mainActivity.getString(R.string.key_sleep);
      final int sleep = mainActivity.getSharedPreferences().getInt(key, MainActivity.SLEEP_MIN);
      final Bundle bundle = new Bundle();
      bundle.putInt(key, sleep);
      // Action only possible if set or playing
      if (isSleepSet || ((int) playImageButton.getTag() == PlaybackStateCompat.STATE_PAUSED)) {
        mediaController.getTransportControls().sendCustomAction(isSleepSet ? RadioService.ACTION_SLEEP_CANCEL : RadioService.ACTION_SLEEP_SET, bundle); // Asynchronous
        if (isSleepSet) {
          mainActivity.tell(R.string.sleep_cancelled);
        } else {
          mainActivity.tell(mainActivity.getString(R.string.sleep_set, sleep));
        }
      }
    }
  }

  private void onNewCurrentRadio() {
    final Radio radio = getCurrentRadio();
    // Manage radio description
    final boolean isVisible = (radio != null);
    albumArtImageView.setVisibility(MainActivityFragment.getVisibleFrom(isVisible));
    playedRadioLinearLayout.setVisibility(MainActivityFragment.getVisibleFrom(isVisible));
    if (isVisible) {
      playedRadioNameTextView.setText(radio.getName());
      albumArtImageView.setImageBitmap(Radio.iconResize(radio.getIcon()));
      setPreferredButton(radio.isPreferred());
    } else {
      setDefaultPlayImageButton();
      setPlayImageButtonVisibility(false, false);
    }
    // Tell listener
    if (listener != null) {
      listener.accept(radio);
    }
  }

  private void copyToClipBoard(@NonNull String string) {
    // Copy selectedInformation to clipboard
    final ClipboardManager clipboard = (ClipboardManager) mainActivity.getSystemService(Context.CLIPBOARD_SERVICE);
    clipboard.setPrimaryClip(ClipData.newPlainText(mainActivity.getString(R.string.radio_information), string));
    // Display a toast message to inform the user
    mainActivity.tell(R.string.copied_to_clipboard);
  }

  private void setDefaultPlayImageButton() {
    playImageButton.setImageResource(R.drawable.ic_play_arrow_white_24dp);
    playImageButton.setTag(PlaybackStateCompat.STATE_PLAYING);
  }

  private void setPlayImageButtonVisibility(boolean isOn, boolean isWaiting) {
    playImageButton.setEnabled(isOn);
    playImageButton.setVisibility(MainActivityFragment.getVisibleFrom(!isWaiting));
    progressBar.setVisibility(MainActivityFragment.getVisibleFrom(isWaiting));
  }

  private void setPreferredButton(boolean isPreferred) {
    preferredImageButton.setImageResource(
      isPreferred ? R.drawable.ic_star_white_30dp : R.drawable.ic_star_border_white_30dp);
  }

  @Override
  public void accept(Consumer<Radio> radioConsumer) {
    listener = radioConsumer;
    // Init listener
    if (listener != null) {
      listener.accept(getCurrentRadio());
    }
  }

  private class MediaControllerCompatCallback extends MediaControllerCompat.Callback {
    // This might happen if the RadioService is killed while the Activity is in the
    // foreground and onStart() has been called (but not onStop())
    @Override
    public void onSessionDestroyed() {
      Log.d(LOG_TAG, "onSessionDestroyed");
      onPlaybackStateChanged(new PlaybackStateCompat.Builder()
        .setState(PlaybackStateCompat.STATE_ERROR, 0, 1.0f, SystemClock.elapsedRealtime())
        .build());
      mediaBrowser.disconnect();
    }

    // Manage play button
    @SuppressLint("SwitchIntDef")
    @Override
    public void onPlaybackStateChanged(@Nullable final PlaybackStateCompat state) {
      final int intState = getState(state);
      Log.d(LOG_TAG, "onPlaybackStateChanged: " + intState);
      // Play button stores state to reach
      setDefaultPlayImageButton();
      switch (intState) {
        case PlaybackStateCompat.STATE_PLAYING:
          // UPnP device doesn't support PAUSE but STOP
          playImageButton.setImageResource(R.drawable.ic_pause_white_24dp);
          playImageButton.setTag(PlaybackStateCompat.STATE_PAUSED);
        case PlaybackStateCompat.STATE_PAUSED:
          setPlayImageButtonVisibility(true, false);
          break;
        case PlaybackStateCompat.STATE_BUFFERING:
        case PlaybackStateCompat.STATE_CONNECTING:
          onNewCurrentRadio();
          setPlayImageButtonVisibility(true, true);
          break;
        case PlaybackStateCompat.STATE_NONE:
        case PlaybackStateCompat.STATE_STOPPED:
          onNewCurrentRadio();
          setPlayImageButtonVisibility(false, false);
          break;
        default:
          // On error, leave radio data visibility
          if (getCurrentRadio() == null) {
            setPlayImageButtonVisibility(false, false);
          } else {
            playImageButton.setImageResource(R.drawable.ic_replay_white_24dp);
            playImageButton.setTag(PlaybackStateCompat.STATE_REWINDING);
            setPlayImageButtonVisibility(true, false);
          }
          mainActivity.tell(R.string.radio_connection_error);
      }
    }

    // Manage dynamic data
    @Override
    public void onMetadataChanged(@Nullable MediaMetadataCompat mediaMetadata) {
      if ((mediaMetadata != null) && RadioService.isValid(mediaMetadata)) {
        // Use SubTitle as notification
        final CharSequence information = mediaMetadata.getDescription().getSubtitle();
        playedRadioInformationTextView.setText(information);
        // Rate in extras
        final Bundle extras = (mediaController == null) ? null : mediaController.getExtras();
        if (extras != null) {
          final String rate = extras.getString(mainActivity.getString(R.string.key_rate));
          if (rate != null) {
            playedRadioRateTextView.setText(rate.isEmpty() ? "" : rate + mainActivity.getString(R.string.kbps));
          }
        }
        // User help for fist valid information after a few time
        informationPressUserHint.show();
      }
    }

    private int getState(@Nullable final PlaybackStateCompat state) {
      return (state == null) ? PlaybackStateCompat.STATE_NONE : state.getState();
    }
  }

  private class MediaBrowserCompatConnectionCallback extends MediaBrowserCompat.ConnectionCallback {
    @Override
    public void onConnected() {
      // Connect radios to other components
      Radios.getInstance().addListener(radiosListener);
      // Get a MediaController for the MediaSession
      mediaController = new MediaControllerCompat(mainActivity, mediaBrowser.getSessionToken());
      // Link to the callback controller
      mediaController.registerCallback(mediaControllerCallback);
      // Sync existing MediaSession state with UI
      onNewCurrentRadio();
      final MediaMetadataCompat mediaMetadataCompat = mediaController.getMetadata();
      if ((mediaMetadataCompat != null) && RadioService.isValid(mediaMetadataCompat)) {
        // Order matters here for display coherence
        mediaControllerCallback.onPlaybackStateChanged(mediaController.getPlaybackState());
        mediaControllerCallback.onMetadataChanged(mediaMetadataCompat);
      }
      // Nota: no mediaBrowser.subscribe here needed
    }

    @Override
    public void onConnectionSuspended() {
      // Disconnect radios to other components
      Radios.getInstance().removeListener(radiosListener);
      // Disconnect callback
      if (mediaController != null) {
        mediaController.unregisterCallback(mediaControllerCallback);
      }
      mediaController = null;
    }

    @Override
    public void onConnectionFailed() {
      Log.d(LOG_TAG, "Connection to RadioService failed");
    }
  }
}