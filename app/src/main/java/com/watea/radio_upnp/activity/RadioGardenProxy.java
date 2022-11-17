package com.watea.radio_upnp.activity;

import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import com.watea.radio_upnp.R;
import com.watea.radio_upnp.model.Radio;
import com.watea.radio_upnp.model.RadioLibrary;
import com.watea.radio_upnp.service.RadioURL;

import org.apache.commons.io.IOUtils;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Clip data:
// "Listen to Radio Campus Bordeaux FM 88.1 from Pessac live on Radio Garden: https://radio.garden/listen/radio-campus-bordeaux-fm-88-1/gHeJPae6"
// Content: http://radio.garden/api/ara/content/channel/{channelId}
//  {
//    "apiVersion": 1,
//    "version": "9bd5454",
//    "data": {
//      "id": "vbFsCngB",
//      "title": "KUTX FM 98.9",
//      "url": "/listen/kutx-98-9/vbFsCngB",
//      "website": "http://www.kutx.org",
//      "secure": true,
//      "place": {
//      "id": "Aq7xeIiB",
//      "title": "Austin TX"
//    },
//    "country": {
//      "id": "GhDXw4EW",
//        "title": "United States"
//    }
//  }
//  }
// Channel: http://radio.garden/api/ara/content/listen/{channelId}/channel.mp3
public class RadioGardenProxy {
  private static final String LOG_TAG = RadioGardenProxy.class.getName();
  private static final Handler handler = new Handler(Looper.getMainLooper());
  private static final String MARKET = "market://details?id=";
  private static final String RADIO_GARDEN_PACKAGE = "com.jonathanpuckey.radiogarden";
  private static final String RADIO_GARDEN = "http://radio.garden/api/ara/content/";
  private static final String CHANNEL = "channel/";
  private static final String LISTEN = "listen/";
  private static final String CHANNEL_MP3 = "/channel.mp3";
  private static final Pattern CLIP_DATA_PATTERN = Pattern.compile("Listen to (.+) from (.+) live on Radio Garden: https://radio.garden/listen/.+/(.+)");
  // <HMI assets
  private final AlertDialog radioGardenAlertDialog;
  // />
  @NonNull
  private final MainActivity mainActivity;

  public RadioGardenProxy(@NonNull MainActivity mainActivity) {
    this.mainActivity = mainActivity;
    radioGardenAlertDialog = new AlertDialog.Builder(mainActivity, R.style.AlertDialogStyle)
      .setTitle(R.string.title_radio_garden)
      .setIcon(R.drawable.ic_search_black_24dp)
      .setView(R.layout.radio_garden_view)
      .setPositiveButton(
        R.string.action_got_it,
        (dialogInterface, i) -> launchRadioGarden(mainActivity.setRadioGardenGotIt()))
      .setNeutralButton(
        R.string.title_radio_garden,
        (dialogInterface, i) -> launchRadioGarden(true))
      // Restore checked item
      .setOnDismissListener(dialogInterface -> mainActivity.checkNavigationMenu())
      .create();
  }

  @NonNull
  private static JSONObject getJson(@NonNull URL url) throws JSONException, IOException {
    return new JSONObject(IOUtils.toString(url.openStream()));
  }

  public void onNewIntent(@NonNull Intent intent) {
    if (Intent.ACTION_SEND.equals(intent.getAction())) {
      mainActivity.tell(R.string.try_to_add_from_radio_garden);
      Log.d(LOG_TAG, "Radio Garden intent received");
      final ClipData clipData = intent.getClipData();
      boolean result = (clipData != null);
      if (result) {
        final ClipData.Item item = clipData.getItemAt(0);
        result = (item != null);
        if (result) {
          Log.d(LOG_TAG, "ClipData: " + item);
          final Matcher matcher = CLIP_DATA_PATTERN.matcher(item.getText());
          result = matcher.find() && (matcher.groupCount() > 2);
          if (result) {
            final String id = matcher.group(3);
            result = (id != null);
            if (result) {
              new Thread(() -> handle(id)).start();
            } else {
              Log.d(LOG_TAG, "Wrong ClipData.Item content");
            }
          } else {
            Log.d(LOG_TAG, "ClipData.Item wrong format");
          }
        } else {
          Log.d(LOG_TAG, "No ClipData.Item");
        }
      } else {
        Log.d(LOG_TAG, "No ClipData");
      }
      if (!result) {
        tell(false);
      }
    }
  }

  public void launchRadioGarden(boolean gotIt) {
    if (gotIt) {
      // Launch Radio Garden or bring user to the market if Radio Garden not installed
      final Intent intent = mainActivity
        .getPackageManager()
        .getLaunchIntentForPackage(RADIO_GARDEN_PACKAGE);
      mainActivity.startActivity(((intent == null) ? new Intent(Intent.ACTION_VIEW) : intent)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        .setData(Uri.parse(MARKET + RADIO_GARDEN_PACKAGE)));
    } else {
      radioGardenAlertDialog.show();
    }
  }

  // Asynchronous call
  private void handle(@NonNull String id) {
    URL tempWebSite = null;
    Bitmap tempIcon = null;
    boolean tempIssOk = false;
    JSONObject tempData = null;
    try {
      final JSONObject jSONObject = getJson(new URL(RADIO_GARDEN + CHANNEL + id));
      tempData = jSONObject.getJSONObject("data");
      final String webSite = tempData.getString("website");
      try {
        tempWebSite = new URL(webSite);
      } catch (MalformedURLException malformedURLException) {
        Log.d(LOG_TAG, "Radio Garden website malformed: " + webSite);
      }
      if (tempWebSite != null) {
        tempIcon = RadioURL.iconSearch(tempWebSite);
      }
      tempIssOk = true;
    } catch (Exception exception) {
      Log.d(LOG_TAG, "handle asynchronous exception", exception);
    }
    final URL webSite = tempWebSite;
    final Bitmap icon = tempIcon;
    final JSONObject data = tempData;
    final boolean isOk = tempIssOk;
    // Synchronous update
    handler.post(() -> {
      boolean toTell = isOk;
      final RadioLibrary radioLibrary = mainActivity.getRadioLibrary();
      if (toTell) {
        try {
          toTell = (radioLibrary != null) && radioLibrary.isOpen() && radioLibrary.add(new Radio(
            data.getString("title"),
            new URL(RADIO_GARDEN + LISTEN + id + CHANNEL_MP3),
            webSite,
            false,
            (icon == null) ? mainActivity.getDefaultIcon() : icon));
          Log.d(LOG_TAG, "Radio added!");
        } catch (Exception exception) {
          Log.d(LOG_TAG, "handle synchronous exception", exception);
        }
      }
      tell(toTell);
      if (toTell) {
        radioLibrary.refreshListeners();
      }
    });
  }

  private void tell(boolean isOk) {
    mainActivity.tell(
      isOk ? R.string.radio_garden_radio_added : R.string.radio_database_update_failed);
  }
}