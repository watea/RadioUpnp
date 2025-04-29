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
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.watea.radio_upnp.R;
import com.watea.radio_upnp.service.RadioURL;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import okhttp3.HttpUrl;
import okhttp3.Request;

public class SearchFragment extends SearchRootFragment {
  private static final String LOG_TAG = SearchFragment.class.getSimpleName();
  private static final int MAX_RADIOS = 200;
  private static final int MAX_TAGS = 200;
  private final List<String> countries = new ArrayList<>();
  private final List<String> radioTags = new ArrayList<>();
  private final List<String> bitrates = new ArrayList<>();
  private EditText nameEditText;
  private Spinner countrySpinner;
  private Spinner radioTagSpinner;
  private Spinner bitrateSpinner;
  private int selectedBitrate = 0;

  @NonNull
  private static HttpUrl.Builder getRadioBrowserBuilder() {
    return new HttpUrl.Builder()
      .scheme("https")
      .host("all.api.radio-browser.info")
      .addPathSegment("json");
  }

  @Override
  public void onPause() {
    super.onPause();
    getSharedPreferences()
      .edit()
      .putString(getString(R.string.key_country), getCountry())
      .putString(getString(R.string.key_radio_tag), getRadioTag())
      .putString(getString(R.string.key_bitrate), getBitrate())
      .apply();
  }

  @Override
  public int getTitle() {
    return R.string.title_search;
  }

  @SuppressLint("InflateParams")
  @Override
  public void onCreateView(@NonNull View view, @Nullable ViewGroup container) {
    final View searchView = super.onCreateView(view, R.layout.view_search);
    countrySpinner = searchView.findViewById(R.id.country_spinner);
    radioTagSpinner = searchView.findViewById(R.id.radio_tag_spinner);
    bitrateSpinner = searchView.findViewById(R.id.rate_spinner);
    nameEditText = searchView.findViewById(R.id.name_edit_text);
  }

  private String getCountry() {
    return getSpinnerValue(countrySpinner);
  }

  private String getRadioTag() {
    return getSpinnerValue(radioTagSpinner);
  }

  private String getBitrate() {
    return getSpinnerValue(bitrateSpinner);
  }

  private String getSpinnerValue(@NonNull Spinner spinner) {
    return (spinner.getSelectedItemPosition() > 0) ? spinner.getSelectedItem().toString() : "";
  }

  private void fetchList(@NonNull List<String> list, @NonNull String url, @NonNull String select) throws IOException, JSONException {
    final Request request = getRequestBuilder().url(url).build();
    final JSONArray array = getJSONArray(request);
    for (int i = 0; i < array.length(); i++) {
      final String name = array.getJSONObject(i).optString("name", "");
      if (!name.isEmpty() && !list.contains(name)) {
        list.add(name);
      }
    }
    list.sort(Comparator.naturalOrder());
    list.add(0, select);
  }

  private void setSpinner(
    @NonNull Spinner spinner,
    @NonNull ArrayAdapter<String> arrayAdapter,
    @NonNull List<String> list,
    int key) {
    spinner.setAdapter(arrayAdapter);
    final int position = list.indexOf(getSharedPreferences().getString(getString(key), ""));
    spinner.setSelection(Math.max(position, 0));
  }

  @Override
  protected void clearDialog() {
    countries.clear();
    radioTags.clear();
    bitrates.clear();
  }

  @NonNull
  @Override
  protected Request getRequest() {
    final String bitrate = getBitrate();
    selectedBitrate = 0;
    try {
      selectedBitrate = Integer.parseInt(bitrate.replace(getString(R.string.kbs), ""));
    } catch (NumberFormatException numberFormatException) {
      Log.w(LOG_TAG, "getRequest: invalid bitrate format: " + bitrate);
    }
    HttpUrl.Builder httpUrlBuilder = getRadioBrowserBuilder().addPathSegments("stations/search");
    httpUrlBuilder = buildQuery(httpUrlBuilder, "country", getCountry());
    httpUrlBuilder = buildQuery(httpUrlBuilder, "tag", getRadioTag());
    httpUrlBuilder = buildQuery(httpUrlBuilder, "name", nameEditText.getText().toString().trim());
    httpUrlBuilder.addQueryParameter("limit", Integer.toString(MAX_RADIOS));
    return getRequestBuilder().url(httpUrlBuilder.build().toString()).build();
  }

  @Override
  protected void fetchDialogItems() throws IOException, JSONException {
    bitrates.addAll(Arrays.asList(getResources().getStringArray(R.array.bitrates_array)));
    bitrates.replaceAll(s -> s + getString(R.string.kbs));
    bitrates.add(0, getString(R.string.bitrate));
    fetchList(
      countries,
      getRadioBrowserBuilder().addPathSegment("countries").build().toString(),
      getString(R.string.country));
    fetchList(
      radioTags,
      getRadioBrowserBuilder().addPathSegment("tags").query("order=stationcount&reverse=true&limit=" + MAX_TAGS).build().toString(),
      getString(R.string.radio_tag));
  }

  @Override
  protected void setDialogItems() {
    assert getContext() != null;
    final ArrayAdapter<String> countriesAdapter = new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_dropdown_item, countries);
    final ArrayAdapter<String> radioTagsAdapter = new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_dropdown_item, radioTags);
    final ArrayAdapter<String> bitratesAdapter = new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_dropdown_item, bitrates);
    setSpinner(countrySpinner, countriesAdapter, countries, R.string.key_country);
    setSpinner(radioTagSpinner, radioTagsAdapter, radioTags, R.string.key_radio_tag);
    setSpinner(bitrateSpinner, bitratesAdapter, bitrates, R.string.key_bitrate);
  }

  @Override
  protected boolean validCurrentRadio(@NonNull JSONObject station) throws JSONException {
    // Bitrate shall be filtered by client
    if (station.optInt("bitrate", 0) >= selectedBitrate) {
      name = station.getString("name");
      stream = station.getString("url_resolved");
      homepage = station.optString("homepage", "");
      icon = null;
      final String favicon = station.optString("favicon", "");
      if (!favicon.isEmpty()) {
        try {
          icon = new RadioURL(new URL(favicon)).getBitmap();
        } catch (MalformedURLException malformedURLException) {
          Log.w(LOG_TAG, "validCurrentRadio: icon fetch error");
        }
      }
      return true;
    }
    return false;
  }
}