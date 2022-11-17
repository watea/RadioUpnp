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
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.watea.radio_upnp.R;
import com.watea.radio_upnp.adapter.RadiosAdapter;
import com.watea.radio_upnp.adapter.UpnpDevicesAdapter;
import com.watea.radio_upnp.model.Radio;
import com.watea.radio_upnp.model.RadioLibrary;

import java.util.List;

public class MainFragment extends MainActivityFragment {
  // <HMI assets
  private FrameLayout defaultFrameLayout;
  private MenuItem dlnaMenuItem;
  private final UpnpDevicesAdapter.ChosenDeviceListener chosenDeviceListener = icon -> {
    if (dlnaMenuItem != null) {
      dlnaMenuItem.setVisible((icon != null));
      if (icon != null) {
        dlnaMenuItem.setIcon(new BitmapDrawable(getResources(), icon));
      }
    }
  };
  private MenuItem preferredMenuItem;
  private AlertDialog radioLongPressAlertDialog;
  private AlertDialog dlnaEnableAlertDialog;
  private AlertDialog preferredRadiosAlertDialog;
  // />
  private int radioClickCount = 0;
  private boolean isPreferredRadios = false;
  private boolean gotItRadioLongPress;
  private boolean gotItDlnaEnable;
  private boolean gotItPreferredRadios;
  private RadiosAdapter radiosAdapter;
  private final RadioLibrary.Listener radioLibraryListener = new RadioLibrary.Listener() {
    @Override
    public void onNewCurrentRadio(@Nullable Radio radio) {
      radiosAdapter.onRefresh(null);
    }

    @Override
    public void onRefresh() {
      assert getRadioLibrary() != null;
      List<Long> radios = isPreferredRadios ?
        getRadioLibrary().getPreferredRadioIds() : getRadioLibrary().getAllRadioIds();
      radiosAdapter.onRefresh(radios);
      defaultFrameLayout.setVisibility(getVisibleFrom(radios.isEmpty()));
    }
  };
  private UpnpDevicesAdapter upnpDevicesAdapter = null;

  @Override
  public void onResume() {
    super.onResume();
    // Force column count
    onConfigurationChanged(getMainActivity().getResources().getConfiguration());
    // Set view
    radioLibraryListener.onRefresh();
    // RadioLibrary changes
    assert getRadioLibrary() != null;
    getRadioLibrary().addListener(radioLibraryListener);
    // UPnP changes
    upnpDevicesAdapter.setChosenDeviceListener(chosenDeviceListener);
  }

  @SuppressLint("NonConstantResourceId")
  @Override
  public boolean onOptionsItemSelected(@NonNull MenuItem item) {
    switch (item.getItemId()) {
      case R.id.action_preferred:
        isPreferredRadios = !isPreferredRadios;
        setPreferredMenuItem();
        radioLibraryListener.onRefresh();
        if (!gotItPreferredRadios) {
          preferredRadiosAlertDialog.show();
        }
        return true;
      case R.id.action_dlna:
        upnpDevicesAdapter.removeChosenUpnpDevice();
        tell(R.string.no_dlna_selection);
        return true;
      default:
        // If we got here, the user's action was not recognized
        // Invoke the superclass to handle it
        return super.onOptionsItemSelected(item);
    }
  }

  @Override
  public void onCreateOptionsMenu(@NonNull Menu menu) {
    dlnaMenuItem = menu.findItem(R.id.action_dlna);
    preferredMenuItem = menu.findItem(R.id.action_preferred);
    chosenDeviceListener.onChosenDeviceChange(upnpDevicesAdapter.getChosenUpnpDeviceIcon());
    setPreferredMenuItem();
  }

  @NonNull
  @Override
  public View.OnClickListener getFloatingActionButtonOnClickListener() {
    return v -> wifiTest(() -> {
      if (getMainActivity().upnpSearch()) {
        getMainActivity().onUpnp();
        if (!gotItDlnaEnable) {
          dlnaEnableAlertDialog.show();
        }
      }
    });
  }

  @NonNull
  @Override
  public View.OnLongClickListener getFloatingActionButtonOnLongClickListener() {
    return v -> wifiTest(() -> getMainActivity().onUpnpReset());
  }

  @Override
  public int getFloatingActionButtonResource() {
    return R.drawable.ic_cast_blue_24dp;
  }

  @Override
  public int getMenuId() {
    return R.menu.menu_main;
  }

  @Override
  public int getTitle() {
    return R.string.title_main;
  }

  @Nullable
  @Override
  public View onCreateView(
    @NonNull LayoutInflater inflater,
    @Nullable ViewGroup container,
    @Nullable Bundle savedInstanceState) {
    // Inflate the view so that graphical objects exists
    final View view = inflater.inflate(R.layout.content_main, container, false);
    RecyclerView radiosRecyclerView = view.findViewById(R.id.radios_recycler_view);
    final int tileSize = getResources().getDimensionPixelSize(R.dimen.tile_size);
    radiosRecyclerView.setLayoutManager(new VarColumnGridLayoutManager(getContext(), tileSize));
    defaultFrameLayout = view.findViewById(R.id.view_radios_default);
    // Adapters (order matters!)
    radiosAdapter = new RadiosAdapter(
      new RadiosAdapter.Listener() {
        @Override
        public void onClick(@NonNull Radio radio) {
          if (getNetworkProxy().isDeviceOffline()) {
            tell(R.string.no_internet);
          } else {
            getMainActivity().startReading(radio);
            if (!gotItRadioLongPress && (radioClickCount++ > 2)) {
              radioLongPressAlertDialog.show();
            }
          }
        }

        @Override
        public boolean onLongClick(@Nullable Uri webPageUri) {
          if (webPageUri == null) {
            tell(R.string.no_web_page);
          } else {
            getMainActivity().startActivity(new Intent(Intent.ACTION_VIEW, webPageUri));
          }
          return true;
        }
      },
      new RadiosAdapter.Callback() {
        @Nullable
        @Override
        public Radio getFrom(@NonNull Long radioId) {
          assert getRadioLibrary() != null;
          return getRadioLibrary().isOpen() ? getRadioLibrary().getFrom(radioId) : null;
        }

        @Override
        public boolean isCurrentRadio(@NonNull Radio radio) {
          assert getRadioLibrary() != null;
          return getRadioLibrary().isCurrentRadio(radio);
        }
      },
      MainActivity.getSmallIconSize());
    radiosRecyclerView.setAdapter(radiosAdapter);
    upnpDevicesAdapter = getMainActivity().getUpnpDevicesAdapter();
    // Build alert dialogs
    radioLongPressAlertDialog = new AlertDialog.Builder(getContext(), R.style.AlertDialogStyle)
      .setMessage(R.string.radio_long_press)
      .setPositiveButton(R.string.action_got_it, (dialogInterface, i) -> gotItRadioLongPress = true)
      .create();
    dlnaEnableAlertDialog = new AlertDialog.Builder(getContext(), R.style.AlertDialogStyle)
      .setMessage(R.string.dlna_enable)
      .setPositiveButton(R.string.action_got_it, (dialogInterface, i) -> gotItDlnaEnable = true)
      .create();
    preferredRadiosAlertDialog = new AlertDialog.Builder(getContext(), R.style.AlertDialogStyle)
      .setMessage(R.string.preferred_radios)
      .setPositiveButton(
        R.string.action_got_it, (dialogInterface, i) -> gotItPreferredRadios = true)
      .create();
    return view;
  }

  @Override
  public void onActivityCreated(@Nullable Bundle savedInstanceState) {
    super.onActivityCreated(savedInstanceState);
    if (savedInstanceState != null) {
      isPreferredRadios = savedInstanceState.getBoolean(getString(R.string.key_preferred_radios));
    }
    // Shared preferences
    SharedPreferences sharedPreferences = getMainActivity().getPreferences(Context.MODE_PRIVATE);
    gotItRadioLongPress =
      sharedPreferences.getBoolean(getString(R.string.key_radio_long_press_got_it), false);
    gotItDlnaEnable =
      sharedPreferences.getBoolean(getString(R.string.key_dlna_enable_got_it), false);
    gotItPreferredRadios =
      sharedPreferences.getBoolean(getString(R.string.key_preferred_radios_got_it), false);
  }

  @Override
  public void onSaveInstanceState(@NonNull Bundle outState) {
    super.onSaveInstanceState(outState);
    outState.putBoolean(getString(R.string.key_preferred_radios), isPreferredRadios);
  }

  @Override
  public void onPause() {
    super.onPause();
    // Context exists
    assert getActivity() != null;
    // Shared preferences
    getActivity()
      .getPreferences(Context.MODE_PRIVATE)
      .edit()
      .putBoolean(getString(R.string.key_radio_long_press_got_it), gotItRadioLongPress)
      .putBoolean(getString(R.string.key_dlna_enable_got_it), gotItDlnaEnable)
      .putBoolean(getString(R.string.key_preferred_radios_got_it), gotItPreferredRadios)
      .apply();
    // Clear resources
    assert getRadioLibrary() != null;
    getRadioLibrary().removeListener(radioLibraryListener);
    upnpDevicesAdapter.setChosenDeviceListener(null);
  }

  private boolean wifiTest(@NonNull Runnable runnable) {
    if (getNetworkProxy().hasWifiIpAddress()) {
      runnable.run();
    } else {
      tell(R.string.lan_required);
    }
    return true;
  }

  private void setPreferredMenuItem() {
    preferredMenuItem.setIcon(
      isPreferredRadios ? R.drawable.ic_star_white_30dp : R.drawable.ic_star_border_white_30dp);
  }

  private static class VarColumnGridLayoutManager extends GridLayoutManager {
    private final int itemWidth;

    public VarColumnGridLayoutManager(Context context, int itemWidth) {
      // Dummy size
      super(context, 1);
      this.itemWidth = itemWidth;
    }

    @Override
    public void onLayoutChildren(RecyclerView.Recycler recycler, RecyclerView.State state) {
      setSpanCount(Math.max(1, getWidth() / itemWidth));
      super.onLayoutChildren(recycler, state);
    }
  }
}