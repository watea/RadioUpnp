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
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Spinner;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.watea.radio_upnp.R;
import com.watea.radio_upnp.adapter.RadiosSearchAdapter;
import com.watea.radio_upnp.model.Radio;
import com.watea.radio_upnp.service.RadioURL;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Hashtable;
import java.util.Map;

public class SearchFragment extends MainActivityFragment {
  private static final String LOG_TAG = SearchFragment.class.getName();
  private static final String DAR_FM_API = "http://api.dar.fm/";
  private static final String DAR_FM_PLAYLIST_REQUEST = DAR_FM_API + "playlist.php?q=@callsign%20";
  private static final String DAR_FM_PAGESIZE = "&pagesize=50";
  private static final String DAR_FM_STATIONS_REQUEST = DAR_FM_API + "darstations.php?station_id=";
  private static final String SPACE_FOR_SEARCH = "%20";
  private static final String COUNTRY_FOR_SEARCH = "@country%20";
  private static final String DAR_FM_PARTNER_TOKEN = "&partner_token=6453742475";
  private static final String DAR_FM_BASE_URL = "http://stream.dar.fm/";
  private static final String DAR_FM_NAME = "name";
  private static final String DAR_FM_WEB_PAGE = "web_page";
  private static final String DAR_FM_ID = "id";
  private FrameLayout defaultFrameLayout;
  private AlertDialog searchAlertDialog;
  private EditText nameEditText;
  private RadiosSearchAdapter radiosSearchAdapter;
  private boolean isFirstStart = true;
  private String[] countryCodes;

  @NonNull
  private static String extractValue(@NonNull Element element, @NonNull String tag) {
    final Elements elements = element.getElementsByTag(tag);
    return elements.isEmpty() ? "" : elements.first().ownText();
  }

  @Override
  public void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    countryCodes = getResources().getStringArray(R.array.iso3166_country_codes);
  }

  @Override
  public void onResume() {
    super.onResume();
    if (isFirstStart) {
      searchAlertDialog.show();
      isFirstStart = false;
    }
  }

  @Override
  public boolean onOptionsItemSelected(@NonNull MenuItem item) {
    if (item.getItemId() == R.id.action_done) {
      getRadios().addAll(radiosSearchAdapter.getSelectedRadios());
      onBackPressed();
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
    return v -> searchAlertDialog.show();
  }

  @Override
  public int getFloatingActionButtonResource() {
    return R.drawable.ic_search_white_24dp;
  }

  @Override
  public int getMenuId() {
    return R.menu.menu_item_modify;
  }

  @Override
  public int getTitle() {
    return R.string.title_search;
  }

  @Override
  protected int getLayout() {
    return R.layout.content_main;
  }

  @SuppressLint("InflateParams")
  @Override
  public void onCreateView(@NonNull View view, @Nullable ViewGroup container) {
    final RecyclerView radiosRecyclerView = view.findViewById(R.id.radios_recycler_view);
    radiosRecyclerView.setLayoutManager(new LinearLayoutManager(view.getContext()));
    defaultFrameLayout = view.findViewById(R.id.view_radios_default);
    final View searchView =
      getMainActivity().getLayoutInflater().inflate(R.layout.view_search, null);
    nameEditText = searchView.findViewById(R.id.name_edit_text);
    final Spinner countrySpinner = searchView.findViewById(R.id.country_spinner);
    // Adapter
    radiosSearchAdapter = new RadiosSearchAdapter(radiosRecyclerView);
    // Build alert dialog
    searchAlertDialog = new AlertDialog.Builder(getContext(), R.style.AlertDialogStyle)
      .setView(searchView)
      .setPositiveButton(
        R.string.action_go,
        (dialogInterface, i) -> search(countryCodes[countrySpinner.getSelectedItemPosition()]))
      .create();
  }

  // Valid countryCode has length == 2
  private void search(@NonNull String countryCode) {
    tell(R.string.wait_search);
    radiosSearchAdapter.clear();
    defaultFrameLayout.setVisibility(View.VISIBLE);
    new Thread(() -> {
      try {
        final Element search = Jsoup.connect(
            DAR_FM_PLAYLIST_REQUEST +
              nameEditText.getText().toString().toUpperCase().replace(" ", SPACE_FOR_SEARCH) +
              ((countryCode.length() == 2) ? COUNTRY_FOR_SEARCH + countryCode : "") +
              DAR_FM_PAGESIZE + DAR_FM_PARTNER_TOKEN)
          .get();
        // Parse data
        for (Element station : search.getElementsByTag("station")) {
          // As stated, may fail
          try {
            final String id = extractValue(station, "station_id");
            if (id.length() > 0) {
              final Map<String, String> radioData = new Hashtable<>();
              radioData.put(DAR_FM_ID, id);
              radioData.put(DAR_FM_NAME, extractValue(station, "callsign"));
              new DarFmDetailSearcher(radioData);
            } else {
              Log.i(LOG_TAG, "Error in data; DAR_FM_PLAYLIST_REQUEST extraction");
            }
          } catch (Exception exception) {
            Log.i(LOG_TAG, "Error performing DAR_FM_PLAYLIST_REQUEST extraction", exception);
          }
        }
      } catch (IOException iOexception) {
        Log.i(LOG_TAG, "Error performing DAR_FM_PLAYLIST_REQUEST search", iOexception);
      }
    }).start();
  }

  private class DarFmDetailSearcher extends Searcher {
    // Map of radio data
    @NonNull
    private final Map<String, String> radioData;
    @Nullable
    private Bitmap icon = null;

    private DarFmDetailSearcher(@NonNull Map<String, String> radioData) {
      super();
      this.radioData = radioData;
      start();
    }

    @Override
    protected void onSearch() {
      try {
        final Element station = Jsoup
          .connect(DAR_FM_STATIONS_REQUEST + radioData.get(DAR_FM_ID) + DAR_FM_PARTNER_TOKEN)
          .get();
        radioData.put(DAR_FM_WEB_PAGE, extractValue(station, "websiteurl"));
        // Order matters
        icon = new RadioURL(new URL(extractValue(station, "imageurl"))).getBitmap();
      } catch (MalformedURLException malformedURLException) {
        Log.i(LOG_TAG, "Error performing icon search", malformedURLException);
      } catch (IOException iOexception) {
        Log.i(LOG_TAG, "Error performing DAR_FM_STATIONS_REQUEST search", iOexception);
      }
    }

    @SuppressLint("SetTextI18n")
    @Override
    protected void onPostSearch() {
      final String radioName = radioData.get(DAR_FM_NAME);
      URL webPage = null;
      try {
        webPage = new URL(radioData.get(DAR_FM_WEB_PAGE));
      } catch (MalformedURLException malformedURLException) {
        Log.i(LOG_TAG, "No web page found for " + radioName);
      }
      try {
        radiosSearchAdapter.add(new Radio(
          (radioName == null) ? "" : radioName.toUpperCase(),
          (icon == null) ? getMainActivity().getDefaultIcon() : icon,
          new URL(DAR_FM_BASE_URL + radioData.get(DAR_FM_ID)),
          webPage));
        // Order matters
        defaultFrameLayout.setVisibility(View.INVISIBLE);
      } catch (MalformedURLException malformedURLException) {
        Log.i(LOG_TAG, "Error adding radio: " + radioName, malformedURLException);
        tell(R.string.dar_fm_failure);
      }
    }
  }
}