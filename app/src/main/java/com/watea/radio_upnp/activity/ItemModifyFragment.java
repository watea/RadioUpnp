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

import android.os.Bundle;
import android.util.Log;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.watea.radio_upnp.R;
import com.watea.radio_upnp.model.Radio;

import java.net.URL;

public class ItemModifyFragment extends ItemFragment {
  private static final String LOG_TAG = ItemModifyFragment.class.getName();
  private Radio radio = null;

  @Override
  public int getTitle() {
    return R.string.title_item_modify;
  }

  // Must be called before creation
  public void set(@NonNull Radio radio) {
    this.radio = radio;
  }

  @Override
  protected void onActivityCreatedFiltered(@Nullable Bundle savedInstanceState) {
    super.onActivityCreatedFiltered(savedInstanceState);
    if (savedInstanceState == null) {
      nameEditText.setText(radio.getName());
      urlEditText.setText(radio.getURL().toString());
      URL webPageURL = this.radio.getWebPageURL();
      if (webPageURL != null) {
        webPageEditText.setText(webPageURL.toString());
      }
      setRadioIcon(radio.getIcon());
    } else {
      // Restore radio
      Long radioId = savedInstanceState.getLong(getString(R.string.key_radio_id));
      radio = getRadioLibrary().getFrom(radioId);
    }
  }

  @Override
  public void onSaveInstanceState(@NonNull Bundle outState) {
    super.onSaveInstanceState(outState);
    // Store radio; may fail
    try {
      outState.putLong(getString(R.string.key_radio_id), radio.getId());
    } catch (Exception exception) {
      outState.clear();
      Log.e(LOG_TAG, "onSaveInstanceState: internal failure", exception);
    }
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    if (!super.onOptionsItemSelected(item)) {
      if (getRadioLibrary().isCurrentRadio(radio)) {
        tell(R.string.not_to_modify);
      } else {
        radio.setName(getRadioName());
        assert urlWatcher.url != null;
        radio.setURL(urlWatcher.url);
        radio.setWebPageURL(webPageWatcher.url);
        assert getIcon() != null;
        radio.setIcon(getIcon());
        // Same file name reused to store icon
        assert getContext() != null;
        if (!(radio.storeIcon(getContext()) &&
          getRadioLibrary().updateFrom(radio.getId(), radio.toContentValues()))) {
          tell(R.string.radio_database_update_failed);
        }
        getBack();
      }
    }
    // Always true
    return true;
  }
}