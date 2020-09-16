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

import android.content.ContentValues;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.watea.radio_upnp.R;
import com.watea.radio_upnp.adapter.RadiosModifyAdapter;
import com.watea.radio_upnp.model.Radio;
import com.watea.radio_upnp.model.RadioSQLContract;

import java.util.List;
import java.util.Objects;

public class ModifyFragment extends MainActivityFragment implements RadiosModifyAdapter.Listener {
  private static final String LOG_TAG = ModifyFragment.class.getName();
  // <HMI assets
  private View radiosDefaultView;
  private RecyclerView radiosRecyclerView;
  // />
  private RadiosModifyAdapter radiosModifyAdapter;

  @Override
  public void onActivityCreated(@Nullable Bundle savedInstanceState) {
    super.onActivityCreated(savedInstanceState);
    // Adapters
    radiosModifyAdapter = new RadiosModifyAdapter(
      Objects.requireNonNull(getActivity()), this, RADIO_ICON_SIZE / 2);
    radiosRecyclerView.setLayoutManager(new LinearLayoutManager(getActivity()));
    // RecyclerView shall be defined for Adapter
    radiosModifyAdapter.attachToRecyclerView(radiosRecyclerView);
    // Adapter shall be defined for RecyclerView
    radiosRecyclerView.setAdapter(radiosModifyAdapter);
  }

  @Override
  public void onResume() {
    super.onResume();
    List<Long> radioIds = radioLibrary.getAllRadioIds();
    radiosModifyAdapter.setRadioIds(radioIds);
    radiosDefaultView.setVisibility((radioIds.size() == 0) ? View.VISIBLE : View.INVISIBLE);
  }

  @NonNull
  @Override
  public View.OnClickListener getFloatingActionButtonOnClickListener() {
    return new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        provider.setFragment(ItemModifyFragment.class);
      }
    };
  }

  @Override
  public int getFloatingActionButtonResource() {
    return R.drawable.ic_playlist_add_black_24dp;
  }

  @Override
  public int getTitle() {
    return R.string.title_modify;
  }

  @Nullable
  @Override
  public View onCreateView(
    @NonNull LayoutInflater inflater, @Nullable ViewGroup container, Bundle savedInstanceState) {
    super.onCreateView(inflater, container, savedInstanceState);
    // Inflate the view so that graphical objects exists
    View view = inflater.inflate(R.layout.content_modify, container, false);
    radiosRecyclerView = view.findViewById(R.id.radios_recycler_view);
    radiosDefaultView = view.findViewById(R.id.view_radios_default);
    return view;
  }

  @Override
  @Nullable
  public Radio getRadioFromId(@NonNull Long radioId) {
    return radioLibrary.getFrom(radioId);
  }

  @Override
  public void onModifyClick(@NonNull Radio radio) {
    ((ItemModifyFragment) provider.setFragment(ItemModifyFragment.class)).set(radio);
  }

  @Override
  public boolean onDelete(@NonNull Long radioId) {
    if (radioLibrary.deleteFrom(radioId) > 0) {
      // Default view is visible if no radio left
      radiosDefaultView.setVisibility(
        radioLibrary.getAllRadioIds().isEmpty() ? View.VISIBLE : View.INVISIBLE);
      return true;
    } else {
      Log.w(LOG_TAG, "Internal failure, radio database update failed");
      return false;
    }
  }

  @Override
  public boolean onMove(@NonNull Long fromRadioId, @NonNull Long toRadioId) {
    ContentValues fromPosition = positionContentValuesOf(fromRadioId);
    ContentValues toPosition = positionContentValuesOf(toRadioId);
    if ((radioLibrary.updateFrom(fromRadioId, toPosition) > 0) &&
      (radioLibrary.updateFrom(toRadioId, fromPosition) > 0)) {
      return true;
    } else {
      Log.w(LOG_TAG, "Internal failure, radio database update failed");
      return false;
    }
  }

  // Utility for database update of radio position
  @NonNull
  private ContentValues positionContentValuesOf(@NonNull Long radioId) {
    ContentValues contentValues = new ContentValues();
    contentValues.put(
      RadioSQLContract.Columns.COLUMN_POSITION, radioLibrary.getPositionFrom(radioId));
    return contentValues;
  }
}