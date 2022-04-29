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

import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.watea.radio_upnp.model.RadioLibrary;

// Upper class for fragments of the main activity
public abstract class MainActivityFragment extends Fragment {
  protected static final int DEFAULT_RESOURCE = -1;
  private static final String LOG_TAG = MainActivityFragment.class.getName();
  private static MainActivity MAIN_ACTIVITY = null;
  private View view;
  private boolean isCreationDone = false;

  // Required empty constructor
  public MainActivityFragment() {
    super();
  }

  // Must be called
  public static void onActivityCreated(@NonNull MainActivity mainActivity) {
    MainActivityFragment.MAIN_ACTIVITY = mainActivity;
  }

  @Nullable
  protected static RadioLibrary getRadioLibrary() {
    return MAIN_ACTIVITY.getRadioLibrary();
  }

  protected static void tell(int message) {
    MAIN_ACTIVITY.tell(message);
  }

  protected static void tell(@NonNull String message) {
    MAIN_ACTIVITY.tell(message);
  }

  @NonNull
  protected static MainActivity getMainActivity() {
    return MAIN_ACTIVITY;
  }

  protected static int getVisibleFrom(boolean isVisible) {
    return isVisible ? View.VISIBLE : View.INVISIBLE;
  }

  @Nullable
  @Override
  public Context getContext() {
    Context context = super.getContext();
    return (context == null) ? MAIN_ACTIVITY : context;
  }

  @Nullable
  @Override
  public View onCreateView(
    @NonNull LayoutInflater inflater, @Nullable ViewGroup container, Bundle savedInstanceState) {
    super.onCreateView(inflater, container, savedInstanceState);
    if (!isCreationDone) {
      view = onCreateViewFiltered(inflater, container);
    }
    return view;
  }

  @Override
  public void onActivityCreated(@Nullable Bundle savedInstanceState) {
    super.onActivityCreated(savedInstanceState);
    Log.d(LOG_TAG, "onActivityCreated: entering, isCreationDone: " + isCreationDone);
    if (!isCreationDone) {
      onActivityCreatedFiltered(savedInstanceState);
      isCreationDone = true;
    }
  }

  @Override
  public void onResume() {
    super.onResume();
    // Decorate
    MAIN_ACTIVITY.onFragmentResume(this);
  }

  @Override
  public boolean onOptionsItemSelected(@NonNull MenuItem item) {
    if (item.getItemId() == android.R.id.home) {
      onBackPressed();
      return true;
    }
    return super.onOptionsItemSelected(item);
  }

  public void onCreateOptionsMenu(@NonNull Menu menu) {
  }

  @NonNull
  public View.OnClickListener getFloatingActionButtonOnClickListener() {
    return v -> {
    };
  }

  @NonNull
  public View.OnLongClickListener getFloatingActionButtonOnLongClickListener() {
    return v -> false;
  }

  public int getFloatingActionButtonResource() {
    return DEFAULT_RESOURCE;
  }

  public int getMenuId() {
    return DEFAULT_RESOURCE;
  }

  public abstract int getTitle();

  protected abstract void onActivityCreatedFiltered(@Nullable Bundle savedInstanceState);

  @Nullable
  protected abstract View onCreateViewFiltered(
    @NonNull LayoutInflater inflater, @Nullable ViewGroup container);

  protected void onBackPressed() {
    MAIN_ACTIVITY.onBackPressed();
  }

  protected boolean isActuallyAdded() {
    return ((getActivity() != null) && isAdded());
  }
}