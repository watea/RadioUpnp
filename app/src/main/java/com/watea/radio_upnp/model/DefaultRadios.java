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

package com.watea.radio_upnp.model;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.util.Log;

import androidx.annotation.NonNull;

import com.watea.radio_upnp.R;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class DefaultRadios {
  private static final String LOG_TAG = DefaultRadios.class.getSimpleName();
  private static final List<DefaultRadio> DEFAULT_RADIOS = Arrays.asList(
    new DefaultRadio(
      "FRANCE INTER",
      R.drawable.logo_france_inter,
      "http://direct.franceinter.fr/live/franceinter-midfi.mp3",
      "https://www.franceinter.fr/"),
    new DefaultRadio(
      "FRANCE CULTURE",
      R.drawable.logo_france_culture,
      "http://direct.franceculture.fr/live/franceculture-midfi.mp3",
      "https://www.franceculture.fr/"),
    new DefaultRadio(
      "FRANCE MUSIQUE",
      R.drawable.logo_france_musique,
      "https://icecast.radiofrance.fr/francemusique-hifi.aac?id=radiofrance&cy-pub=0",
      "https://www.francemusique.fr/"),
    new DefaultRadio(
      "EUROPE1",
      R.drawable.logo_europe1,
      "http://europe1.lmn.fm/europe1.mp3",
      "https://www.europe1.fr/"),
    new DefaultRadio(
      "RFM",
      R.drawable.logo_rfm,
      "http://rfm.lmn.fm/rfm.mp3",
      "http://www.rfm.fr/"),
    new DefaultRadio(
      "SKYROCK",
      R.drawable.logo_skyrock,
      "http://icecast.skyrock.net/s/natio_mp3_128k",
      "https://www.skyrock.com/"),
    new DefaultRadio(
      "EUROPE2",
      R.drawable.logo_europe2,
      "http://europe2.lmn.fm/europe2.mp3",
      "https://www.europe2.fr/"),
    new DefaultRadio(
      "FUN",
      R.drawable.logo_fun,
      "http://icecast.funradio.fr/fun-1-44-128?listen=webCwsBCggNCQgLDQUGBAcGBg",
      "https://www.funradio.fr/"),
    new DefaultRadio(
      "RADIO PARADISE",
      R.drawable.logo_radio_paradise,
      "http://stream.radioparadise.com/flacm",
      "https://www.radioparadise.com/"),
    new DefaultRadio(
      "BBC World Service",
      R.drawable.logo_bbc,
      "https://stream.live.vc.bbcmedia.co.uk/bbc_world_service",
      "http://bbcworldservice.com"),
    new DefaultRadio(
      "FIP",
      R.drawable.logo_fip,
      "http://icecast.radiofrance.fr/fip-hifi.aac",
      "https://www.fip.fr/"),
    new DefaultRadio(
      "MAUI'S Q103",
      R.drawable.logo_q103,
      "http://radio.garden/api/ara/content/listen/ZUwwAb1A/channel.mp3",
      "http://q103maui.com/"),
    new DefaultRadio(
      "DFM DAVID GUETTA",
      R.drawable.logo_dfm,
      "http://radio.garden/api/ara/content/listen/qotcIfno/channel.mp3",
      "https://dfm.ru/"));

  @NonNull
  public static List<Radio> get(@NonNull Context context, int iconSize) {
    return DEFAULT_RADIOS.stream()
      .map(defaultRadio -> {
        try {
          return defaultRadio.getRadioFrom(context, iconSize);
        } catch (MalformedURLException malformedURLException) {
          Log.e(LOG_TAG, "get: MalformedURLException fired on: " + defaultRadio.name);
          return null;
        }
      })
      .filter(Objects::nonNull)
      .collect(Collectors.toList());
  }

  private static class DefaultRadio {
    @NonNull
    private final String name;
    private final int drawable;
    @NonNull
    private final String uRL;
    @NonNull
    private final String webPageURL;

    private DefaultRadio(
      @NonNull String name,
      int drawable,
      @NonNull String uRL,
      @NonNull String webPageURL) {
      this.name = name;
      this.drawable = drawable;
      this.uRL = uRL;
      this.webPageURL = webPageURL;
    }

    @NonNull
    private Radio getRadioFrom(@NonNull Context context, int iconSize)
      throws MalformedURLException {
      return new Radio(
        name,
        Radio.createScaledBitmap(
          BitmapFactory.decodeResource(context.getResources(), drawable), iconSize),
        new URL(uRL),
        new URL(webPageURL));
    }
  }
}