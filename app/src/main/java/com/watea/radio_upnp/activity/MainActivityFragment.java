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

import android.app.Fragment;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.Snackbar;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;

import com.watea.radio_upnp.model.RadioLibrary;

import java.util.Objects;

public abstract class MainActivityFragment extends Fragment {
  public static final int DEFAULT_RESOURCE = -1;
  protected RadioLibrary mRadioLibrary;
  protected Provider mProvider;

  // Required empty constructor
  public MainActivityFragment() {
    super();
  }

  public void onCreateOptionsMenu(@NonNull Menu menu) {
  }

  @Nullable
  @Override
  public View onCreateView(
    LayoutInflater inflater, @Nullable ViewGroup container, Bundle savedInstanceState) {
    super.onCreateView(inflater, container, savedInstanceState);
    setMembers();
    return null;
  }

  @Override
  public void onResume() {
    super.onResume();
    // Activity may have been re-created, so new library instance is used
    setMembers();
    // Decorate
    mProvider.onFragmentResume(this);
  }

  @NonNull
  public View.OnClickListener getFloatingActionButtonOnClickListener() {
    return new View.OnClickListener() {
      @Override
      public void onClick(View view) {
      }
    };
  }

  @NonNull
  public View.OnLongClickListener getFloatingActionButtonOnLongClickListener() {
    return new View.OnLongClickListener() {
      @Override
      public boolean onLongClick(View view) {
        return false;
      }
    };
  }

  public int getFloatingActionButtonResource() {
    return DEFAULT_RESOURCE;
  }

  public int getMenuId() {
    return DEFAULT_RESOURCE;
  }

  public abstract int getTitle();

  // Utility to test if view actually exists (not disposed) and is on screen
  protected boolean isActuallyShown() {
    return ((getView() != null) && getView().isShown());
  }

  protected void tell(int message) {
    Snackbar.make(Objects.requireNonNull(getView()), message, Snackbar.LENGTH_LONG).show();
  }

  protected void tell(@NonNull String message) {
    Snackbar.make(Objects.requireNonNull(getView()), message, Snackbar.LENGTH_LONG).show();
  }

  private void setMembers() {
    mProvider = (Provider) getActivity();
    mRadioLibrary = mProvider.getRadioLibrary();
  }

  public interface Provider {
    @NonNull
    RadioLibrary getRadioLibrary();

    void onFragmentResume(@NonNull MainActivityFragment mainActivityFragment);

    @NonNull
    FloatingActionButton getFloatingActionButton();

    @NonNull
    Fragment setFragment(@NonNull Class<? extends Fragment> fragment);
  }
}