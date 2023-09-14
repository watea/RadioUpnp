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
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.watea.radio_upnp.R;
import com.watea.radio_upnp.adapter.PlayerAdapter;
import com.watea.radio_upnp.model.Radio;
import com.watea.radio_upnp.service.RadioURL;

import java.io.File;
import java.io.FileNotFoundException;
import java.net.MalformedURLException;
import java.net.URL;

public abstract class ItemFragment extends MainActivityFragment {
  private static final String LOG_TAG = ItemFragment.class.getName();
  protected EditText nameEditText;
  protected EditText urlEditText;
  protected EditText webPageEditText;
  protected EditText iconEditText;
  protected UrlWatcher urlWatcher;
  protected UrlWatcher webPageWatcher;
  protected UrlWatcher iconWatcher;
  private Button iconSearchButton;
  private ProgressBar iconSearchProgressBar;
  private Bitmap restoredBitmap = null;

  @Override
  public void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    // Restore icon
    if (savedInstanceState != null) {
      try {
        final String file = savedInstanceState.getString(getString(R.string.key_radio_icon_file));
        restoredBitmap = BitmapFactory.decodeFile(file);
      } catch (Exception exception) {
        Log.e(LOG_TAG, "onCreate: internal failure restoring context", exception);
      }
    }
  }

  @Override
  public void onSaveInstanceState(@NonNull Bundle outState) {
    super.onSaveInstanceState(outState);
    // Save icon; may fail
    try {
      final Bitmap icon = getIcon();
      if (icon != null) {
        final File file = Radio.storeToFile(getMainActivity(), icon, Integer.toString(hashCode()));
        outState.putString(getString(R.string.key_radio_icon_file), file.getPath());
      }
    } catch (FileNotFoundException fileNotFoundException) {
      Log.e(LOG_TAG, "onSaveInstanceState: internal failure", fileNotFoundException);
    }
  }

  @Override
  public void onPause() {
    flushKeyboard();
    super.onPause();
  }

  @Override
  public boolean onOptionsItemSelected(@NonNull MenuItem item) {
    flushKeyboard();
    if (item.getItemId() == R.id.action_done) {
      if (urlWatcher.url == null) {
        tell(R.string.radio_definition_error);
      } else {
        // Action shall be implemented by actual class
        return false;
      }
    } else {
      // If we got here, the user's action was not recognized
      // Invoke the superclass to handle it
      return super.onOptionsItemSelected(item);
    }
    return true;
  }

  @NonNull
  @Override
  public View.OnClickListener getFloatingActionButtonOnClickListener() {
    return v -> {
      if (getNetworkProxy().isDeviceOffline()) {
        tell(R.string.no_internet);
      } else if (urlWatcher.url == null) {
        tell(R.string.connection_test_aborted);
      } else {
        new UrlTester(urlWatcher.url);
      }
    };
  }

  @Override
  public int getFloatingActionButtonResource() {
    return R.drawable.ic_radio_white_24dp;
  }

  @Override
  public int getMenuId() {
    return R.menu.menu_item_modify;
  }

  @Override
  protected int getLayout() {
    return R.layout.content_item;
  }

  @Override
  public void onCreateView(@NonNull View view, @Nullable ViewGroup container) {
    nameEditText = view.findViewById(R.id.name_edit_text);
    urlEditText = view.findViewById(R.id.url_edit_text);
    iconEditText = view.findViewById(R.id.icon_edit_text);
    webPageEditText = view.findViewById(R.id.web_page_edit_text);
    iconSearchButton = view.findViewById(R.id.icon_search_button);
    iconSearchProgressBar = view.findViewById(R.id.icon_search_progress_bar);
    iconSearchButton.setOnClickListener(iconView -> iconSearch());
    final ActivityResultLauncher<Intent> activityResultLauncher = registerForActivityResult(
      new ActivityResultContracts.StartActivityForResult(),
      result -> {
        final Context context = getContext();
        // Do nothing if we were disposed
        if (context == null) {
          return;
        }
        Bitmap bitmap = null;
        if (result.getResultCode() == Activity.RESULT_OK) {
          final Intent data = result.getData();
          final Uri uri = (data == null) ? null : data.getData();
          if (uri != null) {
            try {
              bitmap =
                BitmapFactory.decodeStream(context.getContentResolver().openInputStream(uri));
              if (bitmap != null) {
                setRadioIcon(bitmap);
              }
            } catch (FileNotFoundException fileNotFoundException) {
              Log.i(LOG_TAG, "Error performing icon local search", fileNotFoundException);
            }
          }
        }
        if (bitmap == null) {
          tell(R.string.no_local_icon);
        }
      });
    view.findViewById(R.id.browse_button).setOnClickListener(iconView ->
      activityResultLauncher.launch(new Intent(Intent.ACTION_GET_CONTENT).setType("*/*")));
    urlWatcher = new UrlWatcher(urlEditText);
    webPageWatcher = new UrlWatcher(webPageEditText);
    iconWatcher = new UrlWatcher(iconEditText);
    // Default icon
    setRadioIcon((restoredBitmap == null) ? getMainActivity().getDefaultIcon() : restoredBitmap);
  }

  @NonNull
  protected String getRadioName() {
    return nameEditText.getText().toString().toUpperCase();
  }

  @Nullable
  protected Bitmap getIcon() {
    // nameEditText may be null if called before creation
    return (nameEditText == null) ? null : (Bitmap) nameEditText.getTag();
  }

  protected void setRadioIcon(@NonNull Bitmap icon) {
    final Bitmap resizedIcon = MainActivity.iconResize(Radio.crop(icon));
    nameEditText.setCompoundDrawablesRelativeWithIntrinsicBounds(
      null, new BitmapDrawable(getResources(), resizedIcon), null, null);
    // radioIcon stored as tag
    nameEditText.setTag(resizedIcon);
  }

  private void iconSearch() {
    flushKeyboard();
    if (getNetworkProxy().isDeviceOffline()) {
      tell(R.string.no_internet);
    } else {
      final URL iconUrl = iconWatcher.url;
      final URL webPageUrl = webPageWatcher.url;
      if ((iconUrl == null) && (webPageUrl == null)) {
        tell(R.string.no_icon_found);
      } else {
        // Search in icon URL if available
        if (iconUrl == null) {
          new IconWebSearcher(webPageUrl);
        } else {
          new IconUrlSearcher(iconUrl);
        }
      }
    }
  }

  private void showSearchButton(boolean isShowing) {
    iconSearchButton.setVisibility(getVisibleFrom(isShowing));
    iconSearchProgressBar.setVisibility(getVisibleFrom(!isShowing));
  }

  private void tellWait() {
    flushKeyboard();
    tell(R.string.wait_search);
  }

  // Utility class to listen for URL edition
  protected class UrlWatcher implements TextWatcher {
    private final int defaultColor;
    @NonNull
    private final EditText editText;
    @Nullable
    protected URL url = null;

    private UrlWatcher(@NonNull EditText editText) {
      this.editText = editText;
      this.editText.addTextChangedListener(this);
      defaultColor = this.editText.getCurrentTextColor();
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {
    }

    @Override
    public void afterTextChanged(Editable s) {
      try {
        url = (s.length() > 0) ? new URL(s.toString()) : null;
      } catch (MalformedURLException malformedURLException) {
        url = null;
      }
      editText.setTextColor(
        (url == null) ? ContextCompat.getColor(getMainActivity(), R.color.dark_red) : defaultColor);
    }
  }

  private abstract class IconSearcher extends Searcher {
    @NonNull
    protected final URL url;
    @Nullable
    protected Bitmap icon = null;

    private IconSearcher(@NonNull URL url) {
      super();
      this.url = url;
      tellWait();
      showSearchButton(false);
      start();
    }

    @Override
    protected void onPostSearch() {
      showSearchButton(true);
      if (icon == null) {
        tell(R.string.no_icon_found);
      } else {
        setRadioIcon(icon);
        tell(R.string.icon_updated);
      }
    }
  }

  private class IconWebSearcher extends IconSearcher {
    private IconWebSearcher(@NonNull URL url) {
      super(url);
    }

    @Override
    protected void onSearch() {
      icon = RadioURL.iconSearch(url);
    }
  }

  private class IconUrlSearcher extends IconSearcher {
    private IconUrlSearcher(@NonNull URL url) {
      super(url);
    }

    @Override
    protected void onSearch() {
      icon = new RadioURL(url).getBitmap();
    }
  }

  private class UrlTester extends Searcher {
    @NonNull
    private final URL url;
    @Nullable
    private String streamContent = null;

    private UrlTester(@NonNull URL url) {
      super();
      this.url = url;
      tellWait();
      start();
    }

    @Override
    protected void onSearch() {
      streamContent = new RadioURL(url).getStreamContentType();
    }

    @Override
    protected void onPostSearch() {
      if (streamContent == null) {
        tell(R.string.connection_test_failed);
      } else if (!PlayerAdapter.isHandling(streamContent)) {
        tell(getResources().getString(R.string.mime_not_authorized) + streamContent + ".");
      } else {
        tell(getResources().getString(R.string.connection_test_successful) + streamContent + ".");
      }
    }
  }
}