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
import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SimpleAdapter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.watea.radio_upnp.R;
import com.watea.radio_upnp.adapter.PlayerAdapter;
import com.watea.radio_upnp.model.Radio;
import com.watea.radio_upnp.service.NetworkTester;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Vector;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// ADD or MODIFY modes
// radio = null for ADD
public class ItemModifyFragment extends MainActivityFragment {
  private static final String LOG_TAG = ItemModifyFragment.class.getName();
  private static final String DAR_FM_API = "http://api.dar.fm/";
  private static final String DAR_FM_PLAYLIST_REQUEST = DAR_FM_API + "playlist.php?q=@callsign%20";
  private static final String DAR_FM_STATIONS_REQUEST = DAR_FM_API + "darstations.php?station_id=";
  private static final String WILDCARD = "*";
  private static final String SPACE_FOR_SEARCH = "%20";
  private static final String DAR_FM_PARTNER_TOKEN = "&partner_token=6453742475";
  private static final String DAR_FM_BASE_URL = "http://stream.dar.fm/";
  private static final String DAR_FM_NAME = "name";
  private static final String DAR_FM_WEB_PAGE = "web_page";
  private static final String DAR_FM_ID = "id";
  private static final Pattern PATTERN = Pattern.compile(".*(http.*\\.(png|jpg)).*");
  private static Bitmap DEFAULT_ICON = null;
  // <HMI assets
  private EditText nameEditText;
  private EditText urlEditText;
  private EditText webPageEditText;
  private RadioButton darFmRadioButton;
  private ImageButton searchImageButton;
  private ProgressBar progressBar;
  private UrlWatcher urlWatcher;
  private UrlWatcher webPageWatcher;
  // />
  // Default values; ADD mode
  private Radio radio = null;
  private String radioName = null;
  private String radioUrl = null;
  private String radioWebPage = null;
  private Bitmap radioIcon = null;

  @NonNull
  private static String extractValue(@NonNull Element element, @NonNull String tag) {
    return element.getElementsByTag(tag).first().ownText();
  }

  @Override
  public void onActivityCreated(@Nullable Bundle savedInstanceState) {
    super.onActivityCreated(savedInstanceState);
    // Order matters
    urlWatcher = new UrlWatcher(urlEditText);
    webPageWatcher = new UrlWatcher(webPageEditText);
    // Restore saved state, if any
    if (savedInstanceState == null) {
      nameEditText.setText(radioName);
      urlEditText.setText(radioUrl);
      webPageEditText.setText(radioWebPage);
      darFmRadioButton.setChecked(true);
    } else
      // Robustness; it happens it fails
      try {
        radio = radioLibrary.getFrom(savedInstanceState.getLong(getString(R.string.key_radio_id)));
        radioIcon = BitmapFactory.decodeFile(
          savedInstanceState.getString(getString(R.string.key_radio_icon_file)));
      } catch (Exception exception) {
        Log.e(LOG_TAG, "onActivityCreated: internal failure restoring context");
        radio = null;
        radioIcon = null;
      }
    // Icon init if necessary
    if (radioIcon == null) {
      radioIcon = DEFAULT_ICON;
    }
    setRadioIcon(radioIcon);
    showSearchButton(true);
  }

  @NonNull
  @Override
  public View.OnClickListener getFloatingActionButtonOnClickListener() {
    return view -> {
      if (NetworkTester.isDeviceOffline(Objects.requireNonNull(getActivity()))) {
        tell(R.string.no_internet);
      } else {
        if (urlWatcher.url == null) {
          tell(R.string.connection_test_aborted);
        } else {
          new UrlTester(urlWatcher.url);
        }
      }
    };
  }

  @Override
  public int getFloatingActionButtonResource() {
    return R.drawable.ic_radio_black_24dp;
  }

  @Override
  public int getMenuId() {
    return R.menu.menu_item_modify;
  }

  @Override
  public int getTitle() {
    return isAddMode() ? R.string.title_item_add : R.string.title_item_modify;
  }

  @Nullable
  @Override
  public View onCreateView(
    @NonNull LayoutInflater inflater, @Nullable ViewGroup container, Bundle savedInstanceState) {
    super.onCreateView(inflater, container, savedInstanceState);
    // Inflate the view so that graphical objects exists
    View view = inflater.inflate(R.layout.content_item_modify, container, false);
    nameEditText = view.findViewById(R.id.name_edit_text);
    progressBar = view.findViewById(R.id.progress_bar);
    urlEditText = view.findViewById(R.id.url_edit_text);
    webPageEditText = view.findViewById(R.id.web_page_edit_text);
    darFmRadioButton = view.findViewById(R.id.dar_fm_radio_button);
    searchImageButton = view.findViewById(R.id.search_image_button);
    // Order matters!
    ((RadioGroup) view.findViewById(R.id.search_radio_group)).setOnCheckedChangeListener(
      (group, checkedId) -> {
        boolean isDarFmSelected = (checkedId == R.id.dar_fm_radio_button);
        searchImageButton.setImageResource(
          isDarFmSelected ? R.drawable.ic_search_black_40dp : R.drawable.ic_image_black_40dp);
        webPageEditText.setEnabled(!isDarFmSelected);
        urlEditText.setEnabled(!isDarFmSelected);
      });
    searchImageButton.setOnClickListener(searchView -> {
      flushKeyboard();
      if (NetworkTester.isDeviceOffline(Objects.requireNonNull(getActivity()))) {
        tell(R.string.no_internet);
      } else {
        if (darFmRadioButton.isChecked()) {
          new DarFmSearcher();
        } else {
          showSearchButton(false);
          URL webPageUrl = webPageWatcher.url;
          if (webPageUrl != null) {
            new IconSearcher(webPageUrl);
          }
        }
      }
    });
    if (DEFAULT_ICON == null) {
      createDefaultIcon();
    }
    return view;
  }

  @Override
  public void onSaveInstanceState(@NonNull Bundle outState) {
    super.onSaveInstanceState(outState);
    // May fail
    try {
      // Order matters
      outState.putString(
        getString(R.string.key_radio_icon_file),
        radioLibrary.bitmapToFile(radioIcon, Integer.toString(hashCode())).getPath());
      outState.putLong(getString(R.string.key_radio_id), isAddMode() ? -1 : radio.getId());
    } catch (Exception exception) {
      Log.e(LOG_TAG, "onSaveInstanceState: internal failure");
    }
  }

  @Override
  public void onPause() {
    flushKeyboard();
    super.onPause();
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    flushKeyboard();
    if (item.getItemId() == R.id.action_done) {
      if (urlWatcher.url == null) {
        tell(R.string.radio_definition_error);
      } else {
        if (isAddMode()) {
          radio = new Radio(getRadioName(), urlWatcher.url, webPageWatcher.url);
          try {
            if (radioLibrary.insertAndSaveIcon(radio, radioIcon) <= 0) {
              Log.e(LOG_TAG, "onOptionsItemSelected: internal failure, adding in database");
            }
          } catch (Exception exception) {
            Log.e(LOG_TAG, "onOptionsItemSelected: internal failure, adding icon");
          }
        } else {
          radio.setName(getRadioName());
          radio.setURL(urlWatcher.url);
          radio.setWebPageURL(webPageWatcher.url);
          // Same file name reused to store icon
          try {
            radioLibrary.setRadioIconFile(radio, radioIcon);
          } catch (Exception exception) {
            Log.e(LOG_TAG, "onOptionsItemSelected: internal failure, updating icon");
          }
          if (radioLibrary.updateFrom(radio.getId(), radio.toContentValues()) <= 0) {
            Log.e(LOG_TAG, "onOptionsItemSelected: internal failure, updating database");
          }
        }
        // Back to previous fragment
        Objects.requireNonNull(getFragmentManager()).popBackStack();
      }
    } else {
      // If we got here, the user's action was not recognized
      // Invoke the superclass to handle it
      return super.onOptionsItemSelected(item);
    }
    return true;
  }

  // Must be called before MODIFY mode
  public void set(@NonNull Radio radio) {
    this.radio = radio;
    radioName = this.radio.getName();
    radioUrl = this.radio.getURL().toString();
    URL webPageURL = this.radio.getWebPageURL();
    radioWebPage = (webPageURL == null) ? null : webPageURL.toString();
    radioIcon = this.radio.getIcon();
  }

  private boolean isAddMode() {
    return (radio == null);
  }

  @NonNull
  private String getRadioName() {
    return nameEditText.getText().toString().toUpperCase();
  }

  private void flushKeyboard() {
    Activity activity = getActivity();
    View view = getView();
    assert activity != null;
    assert view != null;
    ((InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE))
      .hideSoftInputFromWindow(view.getWindowToken(), 0);
  }

  private void setRadioIcon(@NonNull Bitmap icon) {
    // Resize bitmap as a square
    int height = icon.getHeight();
    int width = icon.getWidth();
    int min = Math.min(height, width);
    radioIcon = Bitmap.createBitmap(
      icon,
      (width - min) / 2,
      (height - min) / 2,
      min,
      min,
      null,
      false);
    nameEditText.setCompoundDrawablesRelativeWithIntrinsicBounds(
      null,
      new BitmapDrawable(
        getResources(),
        Bitmap.createScaledBitmap(radioIcon, RADIO_ICON_SIZE, RADIO_ICON_SIZE, false)),
      null,
      null);
  }

  private void showSearchButton(boolean isShowing) {
    searchImageButton.setVisibility(isShowing ? View.VISIBLE : View.INVISIBLE);
    progressBar.setVisibility(isShowing ? View.INVISIBLE : View.VISIBLE);
  }

  private void tellWait() {
    flushKeyboard();
    tell(R.string.wait_search);
  }

  private void tellSearch() {
    showSearchButton(false);
    tellWait();
  }

  private void createDefaultIcon() {
    assert getContext() != null;
    Drawable drawable = ContextCompat.getDrawable(getContext(), R.drawable.ic_radio_black_24dp);
    // Deep copy
    assert drawable != null;
    Drawable.ConstantState constantState = drawable.mutate().getConstantState();
    assert constantState != null;
    drawable = constantState.newDrawable();
    drawable.setTint(getResources().getColor(R.color.lightGrey, getContext().getTheme()));
    Canvas canvas = new Canvas();
    DEFAULT_ICON = Bitmap.createBitmap(
      drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
    canvas.setBitmap(DEFAULT_ICON);
    drawable.setBounds(0, 0, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
    drawable.draw(canvas);
  }

  // Abstract class to handle web search
  private abstract class Searcher extends Thread {
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public void run() {
      handler.post(() -> {
        if (isActuallyAdded()) {
          onPostExecute();
        }
      });
    }

    protected void onPostExecute() {
    }
  }

  // Utility class to listen for URL edition
  private class UrlWatcher implements TextWatcher {
    private final int defaultColor;
    private final int errorColor;
    @NonNull
    private final EditText editText;
    @Nullable
    private URL url;

    private UrlWatcher(@NonNull EditText editText) {
      this.editText = editText;
      this.editText.addTextChangedListener(this);
      defaultColor = this.editText.getCurrentTextColor();
      errorColor = ContextCompat.getColor(this.editText.getContext(), R.color.darkRed);
      url = null;
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int ount, int after) {
    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {
    }

    @Override
    public void afterTextChanged(Editable s) {
      boolean isError = false;
      try {
        url = new URL(s.toString());
      } catch (MalformedURLException malformedURLException) {
        if (url != null) {
          tell(R.string.malformed_url_error);
        }
        url = null;
        isError = true;
      }
      editText.setTextColor(isError ? errorColor : defaultColor);
    }
  }

  private class IconSearcher extends Searcher {
    @NonNull
    private final URL url;
    @Nullable
    private Bitmap foundIcon = null;

    private IconSearcher(@NonNull URL url) {
      super();
      this.url = url;
      tellSearch();
      start();
    }

    @Override
    public void run() {
      try {
        Element head = Jsoup.connect(url.toString()).get().head();
        // Parse site data
        Matcher matcher = PATTERN.matcher(head.toString());
        if (matcher.find()) {
          foundIcon = NetworkTester.getBitmapFromUrl(new URL(matcher.group(1)));
        }
      } catch (Exception exception) {
        Log.i(LOG_TAG, "Error performing radio site search");
      }
      super.run();
    }

    @Override
    protected void onPostExecute() {
      showSearchButton(true);
      if (foundIcon == null) {
        tell(R.string.no_icon_found);
      } else {
        setRadioIcon(foundIcon);
        tell(R.string.icon_updated);
      }
    }
  }

  // Launch with no parameter: display the list result or fill content if only one result.
  // Launch with one parameter: fill content.
  private class DarFmSearcher extends Searcher {
    // List of radio datas
    @NonNull
    private final List<Map<String, String>> radios = new Vector<>();
    @Nullable
    private Bitmap foundIcon = null;

    private DarFmSearcher() {
      this(null);
    }

    private DarFmSearcher(@Nullable Map<String, String> radioDatas) {
      super();
      if (radioDatas != null) {
        radios.add(radioDatas);
      }
      tellSearch();
      start();
    }

    @Override
    public void run() {
      if (radios.isEmpty()) {
        try {
          Element search = Jsoup
            .connect(DAR_FM_PLAYLIST_REQUEST + getRadioName().replace(" ", SPACE_FOR_SEARCH) +
              WILDCARD + DAR_FM_PARTNER_TOKEN)
            .get();
          // Parse data
          for (Element station : search.getElementsByTag("station")) {
            // As stated, may fail
            try {
              Map<String, String> map = new Hashtable<>();
              map.put(DAR_FM_ID, extractValue(station, "station_id"));
              map.put(DAR_FM_NAME, extractValue(station, "callsign"));
              radios.add(map);
            } catch (Exception exception) {
              Log.i(LOG_TAG, "Error performing DAR_FM_PLAYLIST_REQUEST extraction", exception);
            }
          }
        } catch (IOException iOexception) {
          Log.i(LOG_TAG, "Error performing DAR_FM_PLAYLIST_REQUEST search", iOexception);
        }
      }
      // When radio is known, fetch data
      if (radios.size() == 1) {
        Map<String, String> foundRadio = radios.get(0);
        try {
          Element station = Jsoup
            .connect(DAR_FM_STATIONS_REQUEST + foundRadio.get(DAR_FM_ID) + DAR_FM_PARTNER_TOKEN)
            .get();
          foundRadio.put(DAR_FM_WEB_PAGE, extractValue(station, "websiteurl"));
          foundIcon = NetworkTester.getBitmapFromUrl(new URL(extractValue(station, "imageurl")));
        } catch (MalformedURLException malformedURLException) {
          Log.i(LOG_TAG, "Error performing icon search");
        } catch (IOException iOexception) {
          Log.i(LOG_TAG, "Error performing DAR_FM_STATIONS_REQUEST search", iOexception);
        }
      }
      super.run();
    }

    @SuppressLint("SetTextI18n")
    @Override
    protected void onPostExecute() {
      switch (radios.size()) {
        case 0:
          tell(R.string.dar_fm_failed);
          break;
        case 1:
          Map<String, String> foundRadio = radios.get(0);
          String radioName = foundRadio.get(DAR_FM_NAME);
          if (radioName != null) {
            nameEditText.setText(radioName.toUpperCase());
          }
          urlEditText.setText(DAR_FM_BASE_URL + foundRadio.get(DAR_FM_ID));
          boolean isDarFmWebPageFound = foundRadio.containsKey(DAR_FM_WEB_PAGE);
          boolean isIconFound = (foundIcon != null);
          if (isDarFmWebPageFound) {
            webPageEditText.setText(foundRadio.get(DAR_FM_WEB_PAGE));
          }
          if (isIconFound) {
            setRadioIcon(foundIcon);
          }
          tell((isIconFound && isDarFmWebPageFound) ?
            R.string.dar_fm_done : R.string.dar_fm_went_wrong);
          break;
        default:
          new AlertDialog
            .Builder(Objects.requireNonNull(getActivity()))
            .setAdapter(
              new SimpleAdapter(
                getActivity(),
                radios,
                R.layout.row_darfm_radio,
                new String[]{DAR_FM_NAME},
                new int[]{R.id.row_darfm_radio_name_text_view}),
              (dialogInterface, i) -> {
                // Call recursively for selected radio
                new DarFmSearcher(radios.get(i));
              })
            .setCancelable(true)
            .create()
            .show();
      }
      showSearchButton(true);
    }
  }

  private class UrlTester extends Searcher {
    @NonNull
    private final URL url;
    @Nullable
    private String streamContent = null;

    private UrlTester(@NonNull URL url) {
      this.url = url;
      tellWait();
      start();
    }

    @Override
    public void run() {
      URL uRL = Radio.getUrlFromM3u(url);
      streamContent = (uRL == null) ? null : NetworkTester.getStreamContentType(uRL);
      super.run();
    }

    @Override
    protected void onPostExecute() {
      if ((streamContent == null) || !PlayerAdapter.isHandling(streamContent)) {
        tell(R.string.connection_test_failed);
      } else {
        tell(getResources().getString(R.string.connection_test_successful) + streamContent + ".");
      }
    }
  }
}