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

package com.watea.radio_upnp.activity;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionCommand;
import androidx.media3.session.SessionToken;

import com.google.common.util.concurrent.ListenableFuture;
import com.watea.radio_upnp.R;
import com.watea.radio_upnp.model.Radio;
import com.watea.radio_upnp.model.Radios;
import com.watea.radio_upnp.service.RadioPlayer;
import com.watea.radio_upnp.service.RadioService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class PlayerController implements Consumer<Consumer<Radio>> {
  // Button tag = action to perform on next click
  private static final int TAG_ACTION_PLAY = 0;
  private static final int TAG_ACTION_PAUSE = 1;
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
  private final Handler longClickHandler = new Handler(Looper.getMainLooper());
  @Nullable
  private ListenableFuture<MediaController> controllerFuture = null;
  // MediaController once connected
  @Nullable
  private MediaController mediaController = null;
  // Callback from media session events
  private final MediaController.Listener mediaControllerListener = new MediaController.Listener() {
    @Override
    public void onExtrasChanged(@NonNull MediaController controller, @NonNull Bundle extras) {
      String rate = extras.getString(mainActivity.getString(R.string.key_bitrate));
      rate = ((rate == null) || rate.isEmpty()) ? "" : rate + mainActivity.getString(R.string.kbps);
      String mimeType = extras.getString(mainActivity.getString(R.string.key_mime_type));
      mimeType = ((mimeType == null) || mimeType.isEmpty()) ? "" : mimeType;
      final String text = rate + (rate.isEmpty() ? "" : " - ") + mimeType;
      playedRadioRateTextView.setText(text);
    }

    // This might happen if the RadioService is killed while the Activity is in the
    // foreground and onStart() has been called (but not onStop())
    @Override
    public void onDisconnected(@NonNull MediaController controller) {
      Log.d(LOG_TAG, "onDisconnected");
      mediaController = null;
      setDefaultPlayImageButton();
      setPlayImageButtonVisibility(false, false);
    }
  };
  @Nullable
  private String pendingRadioName = null;
  private boolean pendingAutoPlay = false;
  private final Radios.Listener radiosListener = new Radios.Listener() {
    @Override
    public void onChange(@NonNull Radio radio) {
      if (radio == getCurrentRadio()) {
        setPreferredButton(radio.isPreferred());
      }
    }

    @Override
    public void onInitEnd() {
      handleInit();
    }
  };
  @Nullable
  private Consumer<Radio> listener = null;
  // Callback from player state changes
  private final Player.Listener playerListener = new Player.Listener() {
    private int previousPlaybackState = Player.STATE_IDLE;

    @Override
    public void onPlaybackStateChanged(int playbackState) {
      updatePlayButton();
    }

    @Override
    public void onIsPlayingChanged(boolean isPlaying) {
      updatePlayButton();
    }

    @Override
    public void onPlayerErrorChanged(@Nullable PlaybackException error) {
      updatePlayButton();
    }

    // Manage dynamic data
    @Override
    public void onMediaMetadataChanged(@NonNull MediaMetadata mediaMetadata) {
      // Use display title metadata (ICY info only, not radio name)
      final CharSequence displayTitle = mediaMetadata.displayTitle;
      playedRadioInformationTextView.setText((displayTitle == null) ? "" : displayTitle);
      if ((displayTitle != null) && !displayTitle.toString().isEmpty()) {
        // User help for first valid information after a few time
        informationPressUserHint.show();
      }
    }

    private void updatePlayButton() {
      assert mediaController != null;
      final int playbackState = mediaController.getPlaybackState();
      final boolean isPlaying = mediaController.isPlaying();
      final boolean hasError = (mediaController.getPlayerError() != null);
      setDefaultPlayImageButton();
      switch (playbackState) {
        case Player.STATE_READY:
          if (isPlaying) {
            playImageButton.setImageResource(R.drawable.ic_pause_white_24dp);
            playImageButton.setTag(TAG_ACTION_PAUSE);
          }
          setPlayImageButtonVisibility(true, false);
          break;
        case Player.STATE_BUFFERING: {
          final boolean isVisible = onNewCurrentRadio();
          setPlayImageButtonVisibility(isVisible, true);
          break;
        }
        default:
          if (hasError) {
            if (getCurrentRadio() == null) {
              setPlayImageButtonVisibility(false, false);
            } else {
              playImageButton.setImageResource(R.drawable.ic_play_arrow_white_24dp);
              playImageButton.setTag(TAG_ACTION_PLAY);
              setPlayImageButtonVisibility(true, false);
            }
            mainActivity.tell(R.string.radio_connection_error);
          } else {
            if (previousPlaybackState != Player.STATE_IDLE) {
              mainActivity.tell(R.string.media_session_stopped);
            }
            setPlayImageButtonVisibility(onNewCurrentRadio(), false);
          }
      }
      previousPlaybackState = playbackState;
    }
  };
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
    final AlertDialog.Builder playlistAlertDialogBuilder = new AlertDialog.Builder(this.mainActivity);
    final SimpleAdapter playlistAdapter = new SimpleAdapter(
      playlistAlertDialogBuilder.getContext(),
      playInformations,
      R.layout.row_playlist,
      new String[]{RadioPlayer.DATE, RadioPlayer.INFORMATION},
      new int[]{R.id.row_playlist_date_text_view, R.id.row_playlist_information_text_view});
    playlistAlertDialog = playlistAlertDialogBuilder
      .setAdapter(playlistAdapter, null)
      .create();
    playlistAlertDialog.getListView().setOnItemLongClickListener((parent, itemView, position, id) -> {
      // Concatenate all entries from the playlist
      copyToClipBoard(playInformations.stream()
        .map(item -> item.get(RadioPlayer.INFORMATION))
        .collect(Collectors.joining("\n\n")));
      // Dismiss the dialog after handling the long press
      playlistAlertDialog.dismiss();
      // Indicate that the long press was handled
      return true;
    });
    playlistAlertDialog.getListView().setOnItemClickListener((parent, rowView, position, id) -> {
      // Get the selected item from the playlist
      final String selectedInformation = playInformations.get(position).get(RadioPlayer.INFORMATION);
      assert selectedInformation != null;
      copyToClipBoard(selectedInformation);
      // Dismiss the dialog after handling the click
      playlistAlertDialog.dismiss();
    });
    // Create view
    albumArtImageView = view.findViewById(R.id.album_art_image_view);
    albumArtImageView.setOnClickListener(v -> {
      if (mediaController == null) { // Should not happen
        mainActivity.tell(R.string.radio_connection_waiting);
      } else {
        playInformations.clear();
        final MediaMetadata metadata = mediaController.getMediaMetadata();
        if (metadata.extras != null) {
          final String playInformation = metadata.extras.getString(RadioPlayer.PLAYLIST);
          if (playInformation != null) {
            playInformations.addAll(RadioPlayer.getPlaylist(playInformation));
          }
        }
        if (playInformations.isEmpty()) {
          mainActivity.tell(R.string.radio_no_playlist);
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
    playImageButton.setOnTouchListener((v, event) -> {
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
        mainActivity.tell(R.string.radio_not_defined);
      } else {
        Radios.getInstance().setPreferred(radio, !radio.isPreferred());
      }
    });
  }

  // Must be called on activity create
  public void onActivityCreate() {
    // Create MediaController
    final SessionToken token = new SessionToken(
      mainActivity, new ComponentName(mainActivity, RadioService.class));
    controllerFuture = new MediaController.Builder(mainActivity, token)
      .setListener(mediaControllerListener)
      .buildAsync();
    controllerFuture.addListener(() -> {
      try {
        // Get a MediaController for the MediaSession
        mediaController = controllerFuture.get();
        mediaController.addListener(playerListener);
        // Connect radios to other components
        Radios.getInstance().addListener(radiosListener);
        // Launch any radio?
        handleInit();
        // Link to the callback controller
        onNewCurrentRadio();
        // Sync existing MediaSession state with UI.
        // Order matters here for display coherence.
        playerListener.onPlaybackStateChanged(mediaController.getPlaybackState());
        final MediaMetadata metadata = mediaController.getMediaMetadata();
        playerListener.onMediaMetadataChanged(metadata);
        mediaControllerListener.onExtrasChanged(mediaController, mediaController.getSessionExtras());
      } catch (ExecutionException | InterruptedException exception) {
        Log.e(LOG_TAG, "onActivityCreate: controller connection failed", exception);
      }
    }, new android.os.Handler(android.os.Looper.getMainLooper())::post);
  }

  // Must be called on activity destroy.
  // Handles services.
  public void onActivityDestroy() {
    // Disconnect radios to other components
    Radios.getInstance().removeListener(radiosListener);
    // Disconnect controller
    if (mediaController != null) {
      mediaController.removeListener(playerListener);
      mediaController.release();
      mediaController = null;
    }
    if (controllerFuture != null) {
      MediaController.releaseFuture(controllerFuture);
      controllerFuture = null;
    }
  }

  public void enableAutoPlay() {
    pendingAutoPlay = true;
  }

  public void startReadingFromName(@NonNull String radioName) {
    pendingRadioName = radioName;
    handleInit();
  }

  public void startReading(@NonNull Radio radio) {
    if (mediaController == null) {
      mainActivity.tell(R.string.radio_connection_waiting);
    } else {
      mediaController.addMediaItem(new MediaItem.Builder().setMediaId(radio.getId()).build());
    }
  }

  @Nullable
  public Radio getCurrentRadio() {
    if (mediaController == null) return null;
    final MediaItem item = mediaController.getCurrentMediaItem();
    if ((item == null) || item.mediaId.isEmpty()) return null;
    return Radios.getInstance().getRadioFromId(item.mediaId);
  }

  @Override
  public void accept(Consumer<Radio> radioConsumer) {
    listener = radioConsumer;
    // Init listener
    if (listener != null) {
      listener.accept(getCurrentRadio());
    }
  }

  private void handleInit() {
    if (!Radios.isInit() || (mediaController == null)) {
      return;
    }
    if (pendingRadioName == null) {
      if (pendingAutoPlay) {
        pendingAutoPlay = false;
        // Start only if no media item is loaded (never started)
        final MediaItem currentItem = mediaController.getCurrentMediaItem();
        if (currentItem == null) {
          final Radio radio = mainActivity.getLastPlayedRadio();
          if (radio != null) {
            startReading(radio);
          }
        }
      }
    } else {
      final Radio radio = Radios.getInstance().getRadioFromName(pendingRadioName);
      if (radio != null) {
        startReading(radio);
      }
      pendingAutoPlay = false;
      pendingRadioName = null;
    }
  }

  @Nullable
  private MediaController getAvailableController() {
    if (mediaController == null) {
      mainActivity.tell(R.string.radio_connection_waiting);
      return null;
    }
    return mediaController;
  }

  private void onPlayClick() {
    final MediaController mediaController = getAvailableController();
    if (mediaController == null) {
      return;
    }
    final Integer tag = (Integer) playImageButton.getTag();
    if (tag == null) {
      Log.e(LOG_TAG, "Internal failure, no action to perform on play button");
      return;
    }
    if (tag == TAG_ACTION_PLAY) {
      mediaController.play();
    } else if (tag == TAG_ACTION_PAUSE) {
      mediaController.pause();
      playLongPressUserHint.show();
    }
  }

  private void onPlayLongClick() {
    final MediaController mediaController = getAvailableController();
    if (mediaController != null) {
      mediaController.stop();
    }
  }

  private void onPlayDoubleClick() {
    final MediaController mediaController = getAvailableController();
    if (mediaController == null) {
      return;
    }
    final Bundle sessionExtras = mediaController.getSessionExtras();
    final boolean isSleepSet = sessionExtras.getBoolean(mainActivity.getString(R.string.key_sleep_set), false);
    final String key = mainActivity.getString(R.string.key_sleep);
    final int sleep = mainActivity.getSharedPreferences().getInt(key, MainActivity.SLEEP_MIN);
    final Bundle bundle = new Bundle();
    bundle.putInt(key, sleep);
    // Action only possible if sleep is set or currently playing (tag = pause = playing)
    if (isSleepSet || (TAG_ACTION_PAUSE == (Integer) playImageButton.getTag())) {
      final String action = isSleepSet ? RadioService.ACTION_SLEEP_CANCEL : RadioService.ACTION_SLEEP_SET;
      mediaController.sendCustomCommand(new SessionCommand(action, Bundle.EMPTY), bundle); // Asynchronous
      if (isSleepSet) {
        mainActivity.tell(R.string.sleep_cancelled);
      } else {
        mainActivity.tell(mainActivity.getString(R.string.sleep_set, sleep));
      }
    }
  }

  // true if valid radio, false otherwise
  private boolean onNewCurrentRadio() {
    final Radio radio = getCurrentRadio();
    // Manage radio description
    final boolean isVisible = (radio != null);
    albumArtImageView.setVisibility(MainActivityFragment.getVisibleFrom(isVisible));
    playedRadioLinearLayout.setVisibility(MainActivityFragment.getVisibleFrom(isVisible));
    if (isVisible) {
      playedRadioNameTextView.setText(radio.getName());
      albumArtImageView.setImageBitmap(radio.getIcon());
      setPreferredButton(radio.isPreferred());
    } else {
      setDefaultPlayImageButton();
      setPlayImageButtonVisibility(false, false);
    }
    // Tell listener
    if (listener != null) {
      listener.accept(radio);
    }
    return isVisible;
  }

  private void copyToClipBoard(@NonNull String string) {
    // Copy to clipboard
    final ClipboardManager clipboard = (ClipboardManager) mainActivity.getSystemService(Context.CLIPBOARD_SERVICE);
    clipboard.setPrimaryClip(ClipData.newPlainText(mainActivity.getString(R.string.radio_information), string));
    // Display a toast message to inform the user
    mainActivity.tell(R.string.copied_to_clipboard);
  }

  private void setDefaultPlayImageButton() {
    playImageButton.setImageResource(R.drawable.ic_play_arrow_white_24dp);
    playImageButton.setTag(TAG_ACTION_PLAY);
  }

  private void setPlayImageButtonVisibility(boolean isOn, boolean isWaiting) {
    isWaiting = isOn && isWaiting;
    playImageButton.setEnabled(isOn);
    playImageButton.setVisibility(MainActivityFragment.getVisibleFrom(!isWaiting));
    progressBar.setVisibility(MainActivityFragment.getVisibleFrom(isWaiting));
  }

  private void setPreferredButton(boolean isPreferred) {
    preferredImageButton.setImageResource(isPreferred ? R.drawable.ic_star_white_24dp : R.drawable.ic_star_border_white_24dp);
  }
}