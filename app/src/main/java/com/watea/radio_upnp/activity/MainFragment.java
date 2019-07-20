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
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.RemoteException;
import android.os.SystemClock;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.design.widget.Snackbar;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.watea.radio_upnp.R;
import com.watea.radio_upnp.adapter.DlnaDevicesAdapter;
import com.watea.radio_upnp.adapter.RadiosAdapter;
import com.watea.radio_upnp.model.DlnaDevice;
import com.watea.radio_upnp.model.Radio;
import com.watea.radio_upnp.model.RadioSQLContract;
import com.watea.radio_upnp.service.NetworkTester;

import org.fourthline.cling.android.AndroidUpnpService;
import org.fourthline.cling.model.meta.Icon;
import org.fourthline.cling.model.meta.RemoteDevice;
import org.fourthline.cling.model.meta.Service;
import org.fourthline.cling.registry.DefaultRegistryListener;
import org.fourthline.cling.registry.Registry;

import java.util.List;

public class MainFragment
  extends MainActivityFragment<MainFragment.Callback>
  implements
  RadiosAdapter.Listener,
  View.OnClickListener,
  View.OnLongClickListener {
  private static final String LOG_TAG = MainFragment.class.getSimpleName();
  private static final String AVTTRANSPORT_SERVICE_ID = "urn:upnp-org:serviceId:AVTransport";
  public final RegistryListener mBrowseRegistryListener = new RegistryListener();
  public final MediaBrowserCompatConnectionCallback mMediaBrowserConnectionCallback =
    new MediaBrowserCompatConnectionCallback();
  private final Handler mHandler = new Handler();
  protected MediaControllerCompat mMediaController = null;
  // <HMI assets
  private View mView;
  private ImageButton mPlayButton;
  private ProgressBar mProgressBar;
  private ImageView mAlbumArt;
  private TextView mPlayedRadioNameTextView;
  private TextView mPlayedRadioInformationTextView;
  private View mRadiosDefaultView;
  private MenuItem mPreferredMenuItem;
  private int mScreenWidthDp;
  private AlertDialog mDlnaAlertDialog;
  private AlertDialog mRadioLongPressAlertDialog;
  private AlertDialog mPlayLongPressAlertDialog;
  private AlertDialog mDlnaEnableAlertDialog;
  // />
  private RadiosAdapter mRadiosAdapter;
  private long mTimeDlnaSearch = 0;
  private DlnaDevice mChosenDlnaDevice = null;
  // Callback from media control
  private final MediaControllerCompat.Callback mMediaControllerCallback =
    new MediaControllerCompat.Callback() {
      // This might happen if the RadioService is killed while the Activity is in the
      // foreground and onStart() has been called (but not onStop())
      @Override
      public void onSessionDestroyed() {
        onPlaybackStateChanged(new PlaybackStateCompat.Builder()
          .setState(
            PlaybackStateCompat.STATE_ERROR,
            0,
            1.0f,
            SystemClock.elapsedRealtime())
          .build());
        Log.d(LOG_TAG, "onSessionDestroyed: RadioService is dead!!!");
      }

      @SuppressLint("SwitchIntDef")
      @Override
      public void onPlaybackStateChanged(@Nullable final PlaybackStateCompat state) {
        int intState = (state == null) ? PlaybackStateCompat.STATE_NONE : state.getState();
        Log.d(LOG_TAG, "onPlaybackStateChanged: " + intState);
        // Play button stores state to reach
        switch (intState) {
          case PlaybackStateCompat.STATE_PLAYING:
            mPlayedRadioNameTextView.setVisibility(View.VISIBLE);
            mPlayedRadioInformationTextView.setVisibility(View.VISIBLE);
            mAlbumArt.setVisibility(View.VISIBLE);
            // DLNA mode?
            if (mChosenDlnaDevice == null) {
              mPlayButton.setImageResource(R.drawable.ic_pause_black_24dp);
              mPlayButton.setTag(PlaybackStateCompat.STATE_PAUSED);
            } else {
              // DLNA device doesn't support PAUSE but STOP
              mPlayButton.setImageResource(R.drawable.ic_stop_black_24dp);
              mPlayButton.setTag(PlaybackStateCompat.STATE_STOPPED);
            }
            mPlayButton.setVisibility(View.VISIBLE);
            mProgressBar.setVisibility(View.INVISIBLE);
            break;
          case PlaybackStateCompat.STATE_PAUSED:
            mPlayedRadioNameTextView.setVisibility(View.VISIBLE);
            mPlayedRadioInformationTextView.setVisibility(View.VISIBLE);
            mAlbumArt.setVisibility(View.VISIBLE);
            mPlayButton.setImageResource(R.drawable.ic_play_arrow_black_24dp);
            mPlayButton.setTag(PlaybackStateCompat.STATE_PLAYING);
            mPlayButton.setVisibility(View.VISIBLE);
            mProgressBar.setVisibility(View.INVISIBLE);
            break;
          case PlaybackStateCompat.STATE_BUFFERING:
          case PlaybackStateCompat.STATE_CONNECTING:
            mPlayedRadioNameTextView.setVisibility(View.VISIBLE);
            mPlayedRadioInformationTextView.setVisibility(View.VISIBLE);
            mAlbumArt.setVisibility(View.VISIBLE);
            mPlayButton.setVisibility(View.INVISIBLE);
            mProgressBar.setVisibility(View.VISIBLE);
            break;
          case PlaybackStateCompat.STATE_NONE:
          case PlaybackStateCompat.STATE_STOPPED:
            mPlayedRadioNameTextView.setVisibility(View.INVISIBLE);
            mPlayedRadioInformationTextView.setVisibility(View.INVISIBLE);
            mAlbumArt.setVisibility(View.INVISIBLE);
            mPlayButton.setVisibility(View.INVISIBLE);
            mProgressBar.setVisibility(View.INVISIBLE);
            break;
          default:
            mPlayedRadioNameTextView.setVisibility(View.INVISIBLE);
            mPlayedRadioInformationTextView.setVisibility(View.INVISIBLE);
            mAlbumArt.setVisibility(View.INVISIBLE);
            mPlayButton.setVisibility(View.INVISIBLE);
            mProgressBar.setVisibility(View.INVISIBLE);
            Snackbar.make(mView, R.string.radio_connection_error, Snackbar.LENGTH_LONG).show();
        }
      }

      @Override
      public void onMetadataChanged(final MediaMetadataCompat mediaMetadata) {
        if (mediaMetadata == null) {
          return;
        }
        mPlayedRadioNameTextView.setText(
          mediaMetadata.getString(MediaMetadataCompat.METADATA_KEY_TITLE));
        String radioInformation =
          mediaMetadata.getString(MediaMetadataCompat.METADATA_KEY_ARTIST);
        mPlayedRadioInformationTextView.setText(radioInformation);
        //noinspection SuspiciousNameCombination
        mAlbumArt.setImageBitmap(
          Bitmap.createScaledBitmap(
            mediaMetadata.getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART),
            mScreenWidthDp,
            mScreenWidthDp,
            false));
      }
    };
  private AndroidUpnpService mAndroidUpnpService = null;
  private boolean mIsPreferredRadios = false;
  private boolean mGotItRadioLongPress;
  private boolean mGotItPlayLongPress;
  private boolean mGotItDlnaEnable;
  // FAB callback
  private final View.OnClickListener mFABOnClickListener = new View.OnClickListener() {
    @Override
    public void onClick(View view) {
      if (!NetworkTester.hasWifiIpAddress(getActivity())) {
        Snackbar.make(mView, R.string.LAN_required, Snackbar.LENGTH_LONG).show();
        return;
      }
      if (mAndroidUpnpService == null) {
        Snackbar.make(mView, R.string.device_no_device_yet, Snackbar.LENGTH_LONG).show();
        return;
      }
      // Do not search more than 1 peer 5 s
      if (System.currentTimeMillis() - mTimeDlnaSearch > 5000) {
        mTimeDlnaSearch = System.currentTimeMillis();
        mAndroidUpnpService.getControlPoint().search();
      }
      mDlnaAlertDialog.show();
      if (!mGotItDlnaEnable) {
        mDlnaEnableAlertDialog.show();
      }
    }
  };
  // DLNA devices management
  private DlnaDevicesAdapter mDlnaDevicesAdapter;
  // FAB callback
  private final View.OnLongClickListener mFABOnLongClickListenerClickListener =
    new View.OnLongClickListener() {
      @Override
      public boolean onLongClick(View view) {
        if (mAndroidUpnpService != null) {
          mAndroidUpnpService.getRegistry().removeAllRemoteDevices();
        }
        mDlnaDevicesAdapter.removeChosenDlnaDevice();
        Snackbar.make(mView, R.string.dlna_reset, Snackbar.LENGTH_LONG).show();
        return true;
      }
    };

  @Nullable
  @Override
  public View onCreateView(
    LayoutInflater inflater, @Nullable ViewGroup container, Bundle savedInstanceState) {
    super.onCreateView(inflater, container, savedInstanceState);
    // Display metrics
    mScreenWidthDp = getResources().getConfiguration().screenWidthDp;
    // Inflate the view so that graphical objects exists
    mView = inflater.inflate(R.layout.content_main, container, false);
    // Fill content including recycler
    // Adapter for DLNA list, created only if we were disposed (known devices lost anyway)
    if (mDlnaDevicesAdapter == null) {
      mDlnaDevicesAdapter = new DlnaDevicesAdapter(getActivity(), R.layout.row_dlna_device);
    }
    // Build alert dialogs
    AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(getActivity())
      .setAdapter(
        mDlnaDevicesAdapter,
        new DialogInterface.OnClickListener() {
          @Override
          public void onClick(DialogInterface dialogInterface, int i) {
            mDlnaDevicesAdapter.onSelection(i);
            Radio radio = getCurrentRadio();
            if ((mDlnaDevicesAdapter.getChosenDlnaDevice() != null) && (radio != null)) {
              startReading(radio);
            }
            mDlnaAlertDialog.dismiss();
          }
        });
    alertDialogBuilder.setCancelable(true);
    mDlnaAlertDialog = alertDialogBuilder.create();
    alertDialogBuilder = new AlertDialog.Builder(getActivity())
      .setMessage(R.string.radio_long_press)
      .setPositiveButton(R.string.got_it, new DialogInterface.OnClickListener() {
        @Override
        public void onClick(DialogInterface dialogInterface, int i) {
          mGotItRadioLongPress = true;
        }
      });
    mRadioLongPressAlertDialog = alertDialogBuilder.create();
    alertDialogBuilder = new AlertDialog.Builder(getActivity())
      .setMessage(R.string.play_long_press)
      .setPositiveButton(R.string.got_it, new DialogInterface.OnClickListener() {
        @Override
        public void onClick(DialogInterface dialogInterface, int i) {
          mGotItPlayLongPress = true;
        }
      });
    mPlayLongPressAlertDialog = alertDialogBuilder.create();
    alertDialogBuilder = new AlertDialog.Builder(getActivity())
      .setMessage(R.string.dlna_enable)
      .setPositiveButton(R.string.got_it, new DialogInterface.OnClickListener() {
        @Override
        public void onClick(DialogInterface dialogInterface, int i) {
          mGotItDlnaEnable = true;
        }
      });
    mDlnaEnableAlertDialog = alertDialogBuilder.create();
    mAlbumArt = mView.findViewById(R.id.album_art);
    mPlayedRadioNameTextView = mView.findViewById(R.id.played_radio_name);
    mPlayedRadioNameTextView.setSelected(true); // For scrolling
    mPlayedRadioInformationTextView = mView.findViewById(R.id.played_radio_information);
    mPlayedRadioInformationTextView.setSelected(true); // For scrolling
    mRadiosAdapter = new RadiosAdapter(getActivity(), this);
    RecyclerView radiosView = mView.findViewById(R.id.radios_view);
    radiosView.setLayoutManager(
      new LinearLayoutManager(getActivity(), LinearLayoutManager.HORIZONTAL, false));
    radiosView.setAdapter(mRadiosAdapter);
    mRadiosDefaultView = mView.findViewById(R.id.radios_default_view);
    mProgressBar = mView.findViewById(R.id.play_waiting);
    mProgressBar.setVisibility(View.INVISIBLE);
    mPlayButton = mView.findViewById(R.id.play);
    mPlayButton.setVisibility(View.INVISIBLE);
    mPlayButton.setOnClickListener(this);
    mPlayButton.setOnLongClickListener(this);
    return mView;
  }

  @Override
  public void onResume() {
    super.onResume();
    // Update view
    mMediaBrowserConnectionCallback.viewSync();
    setRadiosView();
    // Decorate
    mCallback.onResume(
      mFABOnClickListener,
      mFABOnLongClickListenerClickListener,
      R.drawable.ic_cast_black_24dp);
  }

  @Override
  public void onSaveInstanceState(Bundle outState) {
    super.onSaveInstanceState(outState);
    outState.putBoolean(getString(R.string.key_preferred_radios), mIsPreferredRadios);
    // Shared preferences
    getActivity()
      .getPreferences(Context.MODE_PRIVATE)
      .edit()
      .putBoolean(getString(R.string.key_radio_long_press_got_it), mGotItRadioLongPress)
      .putBoolean(getString(R.string.key_play_long_press_got_it), mGotItRadioLongPress)
      .putBoolean(getString(R.string.key_dlna_enable_got_it), mGotItDlnaEnable)
      .apply();
  }

  @Override
  public boolean onOptionsItemSelected(@NonNull MenuItem item) {
    if (item.getItemId() == R.id.action_preferred) {
      mIsPreferredRadios = !mIsPreferredRadios;
      setPreferredMenuItem();
      setRadiosView();
      return true;
    } else {
      // If we got here, the user's action was not recognized
      return false;
    }
  }

  @Override
  public Radio getRadioFromId(@NonNull Long radioId) {
    return mRadioLibrary.getFrom(radioId);
  }

  @Override
  public void onRowClick(@NonNull Radio radio) {
    if (NetworkTester.isDeviceOffline(getActivity())) {
      Snackbar.make(mView, R.string.no_internet, Snackbar.LENGTH_LONG).show();
      return;
    }
    startReading(radio);
    if (!mGotItRadioLongPress) {
      mRadioLongPressAlertDialog.show();
    }
  }

  @Override
  public boolean onPreferredClick(@NonNull Long radioId, Boolean isPreferred) {
    ContentValues values = new ContentValues();
    values.put(RadioSQLContract.Columns.COLUMN_IS_PREFERRED, isPreferred.toString());
    if (mRadioLibrary.updateFrom(radioId, values) > 0) {
      return true;
    } else {
      Log.w(LOG_TAG, "Internal failure, radio database update failed");
      return false;
    }
  }

  // MainActivityFragment
  @Override
  public void onCreateOptionsMenu(@NonNull MenuInflater menuInflater, @NonNull Menu menu) {
    // Inflate the menu; this adds items to the action bar if it is present
    menuInflater.inflate(R.menu.menu_main, menu);
    // Init Preferred MenuItem...
    mPreferredMenuItem = menu.findItem(R.id.action_preferred);
    // ...set it
    setPreferredMenuItem();
  }

  @Override
  public void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    // Restore saved state, if any
    if (savedInstanceState != null) {
      mIsPreferredRadios = savedInstanceState.getBoolean(getString(R.string.key_preferred_radios));
    }
    // Shared preferences
    SharedPreferences sharedPreferences = getActivity().getPreferences(Context.MODE_PRIVATE);
    mGotItRadioLongPress =
      sharedPreferences.getBoolean(getString(R.string.key_radio_long_press_got_it), false);
    mGotItPlayLongPress =
      sharedPreferences.getBoolean(getString(R.string.key_play_long_press_got_it), false);
    mGotItDlnaEnable =
      sharedPreferences.getBoolean(getString(R.string.key_dlna_enable_got_it), false);
  }

  @Override
  public void onClick(View view) {
    // Should not happen
    if (mMediaController == null) {
      Snackbar.make(mView, R.string.radio_connection_waiting, Snackbar.LENGTH_LONG).show();
    } else {
      // Tag on button has stored state to reach
      switch ((int) mPlayButton.getTag()) {
        case PlaybackStateCompat.STATE_PLAYING:
          mMediaController.getTransportControls().play();
          break;
        case PlaybackStateCompat.STATE_PAUSED:
          mMediaController.getTransportControls().pause();
          break;
        case PlaybackStateCompat.STATE_STOPPED:
          mMediaController.getTransportControls().stop();
          break;
        default:
          // Should not happen
          Log.d(LOG_TAG, "Internal failure, no action to perform on play button");
      }
      if (!mGotItPlayLongPress) {
        mPlayLongPressAlertDialog.show();
      }
    }
  }

  @Override
  public boolean onLongClick(View view) {
    // Should not happen
    if (mMediaController == null) {
      Snackbar.make(mView, R.string.radio_connection_waiting, Snackbar.LENGTH_LONG).show();
    } else {
      mMediaController.getTransportControls().stop();
    }
    return true;
  }

  // MediaController controls played radio
  @Nullable
  private Radio getCurrentRadio() {
    return
      (mMediaController == null) ? null : mRadioLibrary.getFrom(mMediaController.getMetadata());
  }

  // Utility to set radio list views
  private void setRadiosView() {
    List<Long> chosenRadioIds =
      mIsPreferredRadios ? mRadioLibrary.getPreferredRadioIds() : mRadioLibrary.getAllRadioIds();
    mRadiosAdapter.setRadioIds(chosenRadioIds);
    // Default view if no radio
    mRadiosDefaultView.setVisibility((chosenRadioIds.size() == 0) ? View.VISIBLE : View.INVISIBLE);
  }

  private void setPreferredMenuItem() {
    mPreferredMenuItem.setIcon(
      mIsPreferredRadios ? R.drawable.ic_star_black_24dp : R.drawable.ic_star_border_black_24dp);
  }

  private void startReading(@NonNull Radio radio) {
    if (mMediaController == null) {
      Snackbar.make(mView, R.string.radio_connection_waiting, Snackbar.LENGTH_LONG).show();
      return;
    }
    Bundle bundle = new Bundle();
    mChosenDlnaDevice = mDlnaDevicesAdapter.getChosenDlnaDevice();
    // DLNA mode?
    if (mChosenDlnaDevice != null) {
      bundle.putInt(getString(R.string.key_dlna_device), mChosenDlnaDevice.hashCode());
    }
    mMediaController.getTransportControls().prepareFromMediaId(radio.getId().toString(), bundle);
  }

  public interface Callback {
    void onResume(
      @NonNull View.OnClickListener floatingActionButtonOnClickListener,
      @NonNull View.OnLongClickListener floatingActionButtonOnLongClickListener,
      int floatingActionButtonResource);
  }

  // MediaController from the MediaBrowser when it has successfully connected
  @SuppressWarnings("WeakerAccess")
  public class MediaBrowserCompatConnectionCallback extends MediaBrowserCompat.ConnectionCallback {
    private MediaBrowserCompat mMediaBrowser = null;

    @Override
    public void onConnected() {
      try {
        // Get a MediaController for the MediaSession
        mMediaController = new MediaControllerCompat(
          getActivity(),
          mMediaBrowser.getSessionToken());
        // Link to the callback controller
        mMediaController.registerCallback(mMediaControllerCallback);
        // Sync existing MediaSession state to the UI
        viewSync();
      } catch (RemoteException remoteException) {
        Log.d(LOG_TAG, String.format("onConnected: problem: %s", remoteException.toString()));
        throw new RuntimeException(remoteException);
      }
      // Nota: no mMediaBrowser.subscribe here needed
    }

    @Override
    public void onConnectionSuspended() {
      if (mMediaController != null) {
        mMediaController.unregisterCallback(mMediaControllerCallback);
        mMediaController = null;
      }
      mMediaBrowser = null;
    }

    @Override
    public void onConnectionFailed() {
      Log.d(LOG_TAG, "Connection to RadioService failed");
    }

    // Must be called on session creation
    @NonNull
    public MediaBrowserCompat setMediaBrowser(@NonNull MediaBrowserCompat mediaBrowserCompat) {
      return mMediaBrowser = mediaBrowserCompat;
    }

    @Nullable
    public MediaBrowserCompat getMediaBrowser() {
      return mMediaBrowser;
    }

    // Must be called on activity release
    public void releaseMediaBrowser() {
      if (mMediaBrowser != null) {
        if (mMediaBrowser.isConnected()) {
          mMediaBrowser.disconnect();
        }
        // Force resource release
        onConnectionSuspended();
      }
    }

    public void viewSync() {
      if (mMediaController != null) {
        mMediaControllerCallback.onMetadataChanged(mMediaController.getMetadata());
        mMediaControllerCallback.onPlaybackStateChanged(mMediaController.getPlaybackState());
      }
    }
  }

  // Listener for DLNA devices
  @SuppressWarnings("WeakerAccess")
  public class RegistryListener extends DefaultRegistryListener {
    @Override
    public void remoteDeviceAdded(Registry registry, final RemoteDevice remoteDevice) {
      // Do nothing if we were disposed
      if (mAndroidUpnpService == null) {
        return;
      }
      for (Service service : remoteDevice.getServices()) {
        if (service.getServiceId().toString().equals(AVTTRANSPORT_SERVICE_ID)) {
          final DlnaDevice dlnaDevice = new DlnaDevice(remoteDevice);
          // Search for icon
          if (remoteDevice.isFullyHydrated()) {
            Icon deviceIcon = getLargestIcon(remoteDevice.getIcons());
            dlnaDevice.setIcon((deviceIcon == null) ? null :
              NetworkTester.getBitmapFromUrl(remoteDevice.normalizeURI(deviceIcon.getUri())));
          }
          // Add DlnaDevice to Adapter
          mHandler.post(
            new Runnable() {
              public void run() {
                int position = mDlnaDevicesAdapter.getPosition(dlnaDevice);
                if (position >= 0) {
                  DlnaDevice foundDlnaDevice = mDlnaDevicesAdapter.getItem(position);
                  if (foundDlnaDevice != null) {
                    foundDlnaDevice.setDevice(remoteDevice);
                    foundDlnaDevice.setIcon(dlnaDevice.getIcon());
                  }
                } else {
                  mDlnaDevicesAdapter.add(dlnaDevice);
                }
              }
            });
          break;
        }
      }
    }

    @Override
    public void remoteDeviceRemoved(Registry registry, final RemoteDevice remoteDevice) {
      // Do nothing if we were disposed
      if (mAndroidUpnpService == null) {
        return;
      }
      mHandler.post(new Runnable() {
        public void run() {
          mDlnaDevicesAdapter.remove(new DlnaDevice(remoteDevice));
        }
      });
    }

    // Must be called on service connection
    public void init(@NonNull AndroidUpnpService androidUpnpService) {
      mAndroidUpnpService = androidUpnpService;
      mHandler.post(new Runnable() {
        public void run() {
          // May be null if adapter onCreateView() not yet called
          if (mDlnaDevicesAdapter != null) {
            mDlnaDevicesAdapter.clear();
          }
        }
      });
    }

    // Must be called on service dispose
    public void release() {
      mAndroidUpnpService = null;
    }

    @Nullable
    private Icon getLargestIcon(Icon[] deviceIcons) {
      Icon icon = null;
      int maxWidth = 0;
      for (Icon deviceIcon : deviceIcons) {
        int width = deviceIcon.getWidth();
        if (width > maxWidth) {
          maxWidth = width;
          icon = deviceIcon;
        }
      }
      return icon;
    }
  }
}