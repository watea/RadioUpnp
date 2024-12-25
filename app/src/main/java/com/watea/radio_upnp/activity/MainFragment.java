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
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Bundle;
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
import com.watea.radio_upnp.adapter.RadiosMainAdapter;
import com.watea.radio_upnp.model.Radio;

public class MainFragment extends MainActivityFragment {
  private FrameLayout defaultFrameLayout;
  private MenuItem upnpMenuItem;
  private MenuItem preferredMenuItem;
  private MainActivity.UserHint radioLongPressUserHint;
  private final RadiosMainAdapter.Listener radiosMainAdapterListener =
    new RadiosMainAdapter.Listener() {
      @Override
      public void onClick(@NonNull Radio radio) {
        if (getNetworkProxy().isDeviceOnline()) {
          getMainActivity().startReading(radio);
          radioLongPressUserHint.show();
        } else {
          tell(R.string.no_internet);
        }
      }

      @Override
      public void onCountChange(boolean isEmpty) {
        defaultFrameLayout.setVisibility(getVisibleFrom(isEmpty));
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
    };
  private MainActivity.UserHint dlnaEnableUserHint;
  private MainActivity.UserHint preferredRadiosUserHint;
  private boolean isPreferredRadios = false;
  private RadiosMainAdapter radiosMainAdapter;

  @Override
  public void onResume() {
    super.onResume();
    // Force column count
    onConfigurationChanged(getMainActivity().getResources().getConfiguration());
    // Set view
    radiosMainAdapter.set(true);
  }

  @Override
  public void onPause() {
    super.onPause();
    // Unset view
    radiosMainAdapter.set(false);
  }

  @SuppressLint("NonConstantResourceId")
  @Override
  public boolean onOptionsItemSelected(@NonNull MenuItem item) {
    switch (item.getItemId()) {
      case R.id.action_preferred:
        isPreferredRadios = !isPreferredRadios;
        radiosMainAdapter.refresh();
        setPreferredMenuItem();
        preferredRadiosUserHint.show();
        return true;
      case R.id.action_upnp:
        getMainActivity().resetSelectedDevice();
        tell(R.string.no_dlna_selection);
        return true;
      default:
        // If we got here, the user's action was not recognized.
        // Invoke the superclass to handle it.
        return super.onOptionsItemSelected(item);
    }
  }

  @Override
  public void onCreateOptionsMenu(@NonNull Menu menu) {
    upnpMenuItem = menu.findItem(R.id.action_upnp);
    getMainActivity().initUpnpIconConsumer();
    preferredMenuItem = menu.findItem(R.id.action_preferred);
    setPreferredMenuItem();
  }

  @NonNull
  @Override
  public View.OnClickListener getFloatingActionButtonOnClickListener() {
    return v -> wifiTest(() -> {
      getMainActivity().onUpnp();
      dlnaEnableUserHint.show();
    });
  }

  @NonNull
  @Override
  public View.OnLongClickListener getFloatingActionButtonOnLongClickListener() {
    return v -> {
      wifiTest(() -> getMainActivity().onUpnp());
      return true;
    };
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

  @Override
  protected int getLayout() {
    return R.layout.content_main;
  }

  @Override
  public void onCreateView(@NonNull View view, @Nullable ViewGroup container) {
    final RecyclerView radiosRecyclerView = view.findViewById(R.id.radios_recycler_view);
    final int tileSize = getResources().getDimensionPixelSize(R.dimen.tile_size);
    radiosRecyclerView.setLayoutManager(new VarColumnGridLayoutManager(getContext(), tileSize));
    defaultFrameLayout = view.findViewById(R.id.default_frame_layout);
    // Adapter
    radiosMainAdapter = new RadiosMainAdapter(
      getMainActivity(),
      () -> isPreferredRadios ? getRadios().getPreferred() : getRadios(),
      radiosRecyclerView,
      radiosMainAdapterListener);
    getMainActivity().setCurrentRadioConsumer(radiosMainAdapter);
    // Build alert dialogs
    radioLongPressUserHint = getMainActivity()
      .new UserHint(R.string.key_radio_long_press_got_it, R.string.radio_long_press, 2);
    dlnaEnableUserHint = getMainActivity()
      .new UserHint(R.string.key_dlna_enable_got_it, R.string.dlna_enable);
    preferredRadiosUserHint = getMainActivity()
      .new UserHint(R.string.key_preferred_radios_got_it, R.string.preferred_radios);
    // Fill menu
    getMainActivity().setUpnpIconConsumer(bitmap -> {
      if (upnpMenuItem != null) {
        upnpMenuItem.setVisible((bitmap != null));
        if (bitmap != null) {
          upnpMenuItem.setIcon(new BitmapDrawable(getResources(), bitmap));
        }
      }
    });
  }

  @Override
  public void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    if (savedInstanceState != null) {
      isPreferredRadios = savedInstanceState.getBoolean(getString(R.string.key_preferred_radios));
    }
  }

  @Override
  public void onSaveInstanceState(@NonNull Bundle outState) {
    super.onSaveInstanceState(outState);
    outState.putBoolean(getString(R.string.key_preferred_radios), isPreferredRadios);
  }

  private void wifiTest(@NonNull Runnable runnable) {
    if (getNetworkProxy().isOnWifi()) {
      runnable.run();
    } else {
      tell(R.string.lan_required);
    }
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