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

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.watea.radio_upnp.service.NetworkProxy;

// Upper class for fragments of the main activity
public abstract class MainActivityFragment extends Fragment {
  protected static final int DEFAULT_RESOURCE = -1;
  @Nullable
  private View view = null;
  private ViewGroup container;
  private int yScrollPosition = 0;

  protected static int getVisibleFrom(boolean isVisible) {
    return isVisible ? View.VISIBLE : View.INVISIBLE;
  }

  @Nullable
  @Override
  public View onCreateView(
    @NonNull LayoutInflater inflater,
    @Nullable ViewGroup container,
    @Nullable Bundle savedInstanceState) {
    assert container != null;
    this.container = container;
    if (view == null) {
      onCreateView(view = inflater.inflate(getLayout(), container, false), container);
    }
    assert view != null;
    return view;
  }

  @Override
  public void onResume() {
    super.onResume();
    // Restore scroll
    container.setScrollY(yScrollPosition);
    // Decorate
    getMainActivity().onFragmentResume(this);
  }

  @Override
  public void onPause() {
    super.onPause();
    // Store scroll position.
    // Note: not saved InstanceState as not necessary for user experience.
    yScrollPosition = container.getScrollY();
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

  protected abstract int getLayout();

  protected abstract void onCreateView(@NonNull View view, @Nullable ViewGroup container);

  @NonNull
  protected SharedPreferences getSharedPreferences() {
    return getMainActivity().getSharedPreferences();
  }

  protected void tell(int message) {
    getMainActivity().tell(message);
  }

  protected void tell(@NonNull String message) {
    getMainActivity().tell(message);
  }

  @NonNull
  protected NetworkProxy getNetworkProxy() {
    return getMainActivity().getNetworkProxy();
  }

  @NonNull
  protected MainActivity getMainActivity() {
    assert getActivity() != null;
    return (MainActivity) getActivity();
  }

  protected void onBackPressed() {
    getMainActivity().getOnBackPressedDispatcher().onBackPressed();
  }

  protected void flushKeyboard() {
    final View focus = getMainActivity().getCurrentFocus();
    if (focus != null) {
      flushKeyboard(focus);
    }
  }

  protected void flushKeyboard(@NonNull View focus) {
    ((InputMethodManager) getMainActivity().getSystemService(Context.INPUT_METHOD_SERVICE))
      .hideSoftInputFromWindow(focus.getWindowToken(), 0);
  }

  protected void protectedRunOnUiThread(@NonNull Runnable runnable) {
    final Activity activity = getActivity();
    if (activity != null) {
      activity.runOnUiThread(() -> {
        final Activity actuallActivity = getActivity();
        if ((actuallActivity != null) && isAdded()) {
          runnable.run();
        }
      });
    }
  }

  // Abstract class to handle web search
  protected abstract class Searcher extends Thread {
    @Override
    public void run() {
      onSearch();
      protectedRunOnUiThread(this::onPostSearch);
    }

    protected abstract void onSearch();

    protected abstract void onPostSearch();
  }
}