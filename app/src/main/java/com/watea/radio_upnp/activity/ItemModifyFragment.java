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
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AlertDialog;
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

import com.watea.radio_upnp.R;
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

import static com.watea.radio_upnp.service.NetworkTester.getStreamContentType;

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
    // Restore saved state, if any
    if (savedInstanceState != null) {
      radio = radioLibrary.getFrom(savedInstanceState.getLong(getString(R.string.key_radio_id)));
      radioName = savedInstanceState.getString(getString(R.string.key_radio_name));
      radioUrl = savedInstanceState.getString(getString(R.string.key_radio_url));
      radioWebPage = savedInstanceState.getString(getString(R.string.key_radio_web_page));
      radioIcon = BitmapFactory.decodeFile(
        savedInstanceState.getString(getString(R.string.key_radio_icon_file)));
    }
    // Init if necessary
    if (radioIcon == null) {
      radioIcon = BitmapFactory.decodeResource(getActivity().getResources(), R.drawable.ic_radio);
    }
    // Set views
    nameEditText.setText(radioName);
    setRadioIcon(radioIcon);
    urlEditText.setText(radioUrl);
    webPageEditText.setText(radioWebPage);
    urlWatcher = new UrlWatcher(urlEditText);
    webPageWatcher = new UrlWatcher(webPageEditText);
  }

  @Override
  public void onSaveInstanceState(Bundle outState) {
    super.onSaveInstanceState(outState);
    // "set" values...
    outState.putLong(getString(R.string.key_radio_id), isAddMode() ? -1 : radio.getId());
    // ...others
    outState.putString(getString(R.string.key_radio_name), nameEditText.getText().toString());
    outState.putString(getString(R.string.key_radio_url), urlEditText.getText().toString());
    outState.putString(getString(R.string.key_radio_web_page), webPageEditText.getText().toString());
    outState.putString(getString(R.string.key_radio_icon_file),
      radioLibrary.bitmapToFile(radioIcon, Integer.toString(hashCode())).getPath());
    outState.putBoolean(getString(R.string.key_dar_fm_checked), darFmRadioButton.isChecked());
  }

  @Override
  public void onPause() {
    flushKeyboard(Objects.requireNonNull(getView()));
    super.onPause();
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    flushKeyboard(Objects.requireNonNull(getView()));
    if (item.getItemId() == R.id.action_done) {
      if (urlWatcher.url == null) {
        tell(R.string.radio_definition_error);
      } else {
        if (isAddMode()) {
          radio = new Radio(getRadioName(), urlWatcher.url, webPageWatcher.url);
          if (radioLibrary.insertAndSaveIcon(radio, radioIcon) <= 0) {
            Log.e(LOG_TAG, "onOptionsItemSelected: internal failure, adding in database");
          }
        } else {
          radio.setName(getRadioName());
          radio.setURL(urlWatcher.url);
          radio.setWebPageURL(webPageWatcher.url);
          // Same file name reused to store icon
          radioLibrary.setRadioIconFile(radio, radioIcon);
          if (radioLibrary.updateFrom(radio.getId(), radio.toContentValues()) <= 0) {
            Log.e(LOG_TAG, "onOptionsItemSelected: internal failure, updating database");
          }
        }
        // Back to previous fragment
        getFragmentManager().popBackStack();
      }
    } else {
      // If we got here, the user's action was not recognized
      // Invoke the superclass to handle it
      return super.onOptionsItemSelected(item);
    }
    return true;
  }

  @Nullable
  @Override
  public View onCreateView(
    LayoutInflater inflater, @Nullable ViewGroup container, Bundle savedInstanceState) {
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
      new RadioGroup.OnCheckedChangeListener() {
        @Override
        public void onCheckedChanged(RadioGroup group, int checkedId) {
          boolean isDarFmSelected = (checkedId == R.id.dar_fm_radio_button);
          searchImageButton.setImageResource(
            isDarFmSelected ? R.drawable.ic_search_black_40dp : R.drawable.ic_image_black_40dp);
          webPageEditText.setEnabled(!isDarFmSelected);
          urlEditText.setEnabled(!isDarFmSelected);
        }

      });
    darFmRadioButton.setChecked(
      (savedInstanceState == null) ||
        savedInstanceState.getBoolean(getString(R.string.key_dar_fm_checked)));
    searchImageButton.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        flushKeyboard(Objects.requireNonNull(getView()));
        if (NetworkTester.isDeviceOffline(getActivity())) {
          tell(R.string.no_internet);
        } else {
          if (darFmRadioButton.isChecked()) {
            //noinspection unchecked
            new DarFmSearcher().execute();
          } else {
            showSearchButton(false);
            new IconSearcher().execute(webPageWatcher.url);
          }
        }
      }
    });
    showSearchButton(true);
    return view;
  }

  @NonNull
  @Override
  public View.OnClickListener getFloatingActionButtonOnClickListener() {
    return new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        if (NetworkTester.isDeviceOffline(getActivity())) {
          tell(R.string.no_internet);
        } else {
          if (urlWatcher.url == null) {
            tell(R.string.connection_test_aborted);
          } else {
            new UrlTester().execute(urlWatcher.url);
          }
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

  // Must be called before MODIFY mode
  public void set(@NonNull Radio radio) {
    this.radio = radio;
    radioName = this.radio.getName();
    radioUrl = this.radio.getURL().toString();
    URL webPageURL = this.radio.getWebPageURL();
    radioWebPage = (webPageURL == null) ? null : webPageURL.toString();
    radioIcon = this.radio.getIcon();
  }

  public boolean isAddMode() {
    return (radio == null);
  }

  @NonNull
  private String getRadioName() {
    return nameEditText.getText().toString().toUpperCase();
  }

  private void flushKeyboard(@NonNull View view) {
    InputMethodManager inputMethodManager =
      (InputMethodManager) getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
    if (inputMethodManager != null) {
      inputMethodManager.hideSoftInputFromWindow(view.getWindowToken(), 0);
    }
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
    flushKeyboard(Objects.requireNonNull(getView()));
    tell(R.string.wait_search);
  }

  private void tellSearch() {
    showSearchButton(false);
    tellWait();
  }

  // Utility class to listen for URL edition
  private class UrlWatcher implements TextWatcher {
    private final int defaultColor;
    @NonNull
    private final EditText editText;
    @Nullable
    private URL url;

    private UrlWatcher(@NonNull EditText editText) {
      this.editText = editText;
      this.editText.addTextChangedListener(this);
      defaultColor = this.editText.getCurrentTextColor();
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
      try {
        url = new URL(s.toString());
        editText.setTextColor(defaultColor);
      } catch (MalformedURLException malformedURLException) {
        if (url != null) {
          tell(R.string.malformed_url_error);
        }
        url = null;
        editText.setTextColor(ContextCompat.getColor(getActivity(), R.color.colorError));
      }
    }
  }

  @SuppressLint("StaticFieldLeak")
  private class IconSearcher extends AsyncTask<URL, Void, Bitmap> {
    @Override
    protected Bitmap doInBackground(URL... urls) {
      Bitmap foundIcon = null;
      try {
        Element head = Jsoup.connect(urls[0].toString()).get().head();
        // Parse site data
        Matcher matcher = PATTERN.matcher(head.toString());
        if (matcher.find()) {
          foundIcon = NetworkTester.getBitmapFromUrl(new URL(matcher.group(1)));
        }
      } catch (Exception exception) {
        Log.i(LOG_TAG, "Error performing radio site search");
      }
      return foundIcon;
    }

    @Override
    protected void onPreExecute() {
      super.onPreExecute();
      tellSearch();
    }

    @Override
    protected void onPostExecute(Bitmap foundIcon) {
      super.onPostExecute(foundIcon);
      if (isActuallyAdded()) {
        showSearchButton(true);
        if (foundIcon == null) {
          tell(R.string.no_icon_found);
        } else {
          setRadioIcon(foundIcon);
          tell(R.string.icon_updated);
        }
      }
    }
  }

  // Launch with no parameter: display the list result or fill content if only one result.
  // Launch with one parameter: fill content.
  @SuppressLint("StaticFieldLeak")
  private class DarFmSearcher
    extends AsyncTask<Map<String, String>, Void, List<Map<String, String>>> {
    @Nullable
    private Bitmap foundIcon;

    @SafeVarargs
    @Override
    protected final List<Map<String, String>> doInBackground(Map<String, String>... radios) {
      List<Map<String, String>> darFmRadios = new Vector<>();
      foundIcon = null;
      if (radios.length == 0) {
        try {
          Element search = Jsoup
            .connect(DAR_FM_PLAYLIST_REQUEST + getRadioName().replace(" ", SPACE_FOR_SEARCH) +
              WILDCARD + DAR_FM_PARTNER_TOKEN)
            .get();
          // Parse data
          for (Element station : search.getElementsByTag("station")) {
            Map<String, String> map = new Hashtable<>();
            map.put(DAR_FM_ID, extractValue(station, "station_id"));
            map.put(DAR_FM_NAME, extractValue(station, "callsign"));
            darFmRadios.add(map);
          }
        } catch (IOException iOexception) {
          Log.i(LOG_TAG, "Error performing DAR_FM_PLAYLIST_REQUEST search", iOexception);
        }
      } else {
        darFmRadios.add(radios[0]);
      }
      // When radio is known, fetch data
      if (darFmRadios.size() == 1) {
        Map<String, String> foundRadio = darFmRadios.get(0);
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
      return darFmRadios;
    }

    @Override
    protected void onPreExecute() {
      super.onPreExecute();
      tellSearch();
    }

    @SuppressLint("SetTextI18n")
    @Override
    protected void onPostExecute(@NonNull final List<Map<String, String>> darFmRadios) {
      super.onPostExecute(darFmRadios);
      if (isActuallyAdded()) {
        switch (darFmRadios.size()) {
          case 0:
            tell(R.string.dar_fm_failed);
            break;
          case 1:
            Map<String, String> foundRadio = darFmRadios.get(0);
            nameEditText.setText(Objects.requireNonNull(foundRadio.get(DAR_FM_NAME)).toUpperCase());
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
              .Builder(getActivity())
              .setAdapter(
                new SimpleAdapter(
                  getActivity(),
                  darFmRadios,
                  R.layout.row_darfm_radio,
                  new String[]{DAR_FM_NAME},
                  new int[]{R.id.row_darfm_radio_name_text_view}),
                new DialogInterface.OnClickListener() {
                  @Override
                  public void onClick(DialogInterface dialogInterface, int i) {
                    //noinspection unchecked
                    new DarFmSearcher().execute(darFmRadios.get(i));
                  }
                })
              .setCancelable(true)
              .create()
              .show();
        }
        showSearchButton(true);
      }
    }
  }

  @SuppressLint("StaticFieldLeak")
  private class UrlTester extends AsyncTask<URL, Void, String> {
    @Override
    protected String doInBackground(URL... urls) {
      return getStreamContentType(urls[0]);
    }

    @Override
    protected void onPreExecute() {
      super.onPreExecute();
      tellWait();
    }

    @Override
    protected void onPostExecute(String streamContent) {
      super.onPostExecute(streamContent);
      if (isActuallyAdded()) {
        if ((streamContent == null) || !streamContent.contains("audio/")) {
          tell(R.string.connection_test_failed);
        } else {
          tell(getResources().getString(R.string.connection_test_successful) + streamContent + ".");
        }
      }
    }
  }
}