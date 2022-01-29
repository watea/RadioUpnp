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

import static com.watea.radio_upnp.activity.MainActivity.RADIO_ICON_SIZE;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.watea.radio_upnp.R;
import com.watea.radio_upnp.adapter.RadiosModifyAdapter;
import com.watea.radio_upnp.model.Radio;
import com.watea.radio_upnp.model.RadioLibrary;

public class ModifyFragment extends MainActivityFragment implements RadiosModifyAdapter.Listener {
  // <HMI assets
  private RecyclerView radiosRecyclerView;
  private FrameLayout defaultFrameLayout;
  // />
  private RadiosModifyAdapter radiosModifyAdapter;

  @Override
  public void onResume() {
    super.onResume();
    radiosModifyAdapter.onResume();
  }

  @NonNull
  @Override
  public View.OnClickListener getFloatingActionButtonOnClickListener() {
    return v -> getMainActivity().setFragment(ItemAddFragment.class);
  }

  @Override
  public int getFloatingActionButtonResource() {
    return R.drawable.ic_playlist_add_white_24dp;
  }

  @Override
  public int getTitle() {
    return R.string.title_modify;
  }

  @Override
  protected void onActivityCreatedFiltered(@Nullable Bundle savedInstanceState) {
    // Context exists
    assert getContext() != null;
    radiosRecyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
    // Adapters
    radiosModifyAdapter = new RadiosModifyAdapter(
      getContext(),
      this,
      RADIO_ICON_SIZE / 2,
      radiosRecyclerView);
  }

  @Nullable
  @Override
  protected View onCreateViewFiltered(
    @NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
    // Inflate the view so that graphical objects exists
    final View view = inflater.inflate(R.layout.content_main, container, false);
    radiosRecyclerView = view.findViewById(R.id.radios_recycler_view);
    defaultFrameLayout = view.findViewById(R.id.view_radios_default);
    return view;
  }

  @Override
  public void onPause() {
    super.onPause();
    radiosModifyAdapter.onPause();
  }

  @Override
  public void onModifyClick(@NonNull Radio radio) {
    ((ItemModifyFragment) getMainActivity().setFragment(ItemModifyFragment.class)).set(radio);
  }

  // Radio shall not be changed if currently played
  @Override
  public boolean onCheckChange(@NonNull Radio radio) {
    if (getRadioLibrary().isCurrentRadio(radio)) {
      tell(R.string.not_to_delete);
      return false;
    }
    return true;
  }

  @Override
  public void onEmpty(boolean isEmpty) {
    defaultFrameLayout.setVisibility(isEmpty ? View.VISIBLE : View.INVISIBLE);
  }

  @NonNull
  @Override
  public RadioLibrary getRadioLibraryAccess() {
    return getRadioLibrary();
  }
}