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

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Spinner;

import com.watea.radio_upnp.BuildConfig;
import com.watea.radio_upnp.R;
import com.watea.radio_upnp.util.IabHelper;
import com.watea.radio_upnp.util.IabResult;
import com.watea.radio_upnp.util.Purchase;

import java.util.Objects;

import static com.watea.radio_upnp.util.IabHelper.BILLING_RESPONSE_RESULT_USER_CANCELED;
import static com.watea.radio_upnp.util.IabHelper.IABHELPER_USER_CANCELLED;

public class DonationFragment extends MainActivityFragment {
  private static final String LOG_TAG = DonationFragment.class.getName();
  private static final String PUBKEY = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAqPtND+7yhoE27XNfflnFLzGRaDZFJBt+xVkhxUeTa7YifViIWBUpBkTpCyl/DjWvNSLH7rqXHeU11NFFFmuTFn6GKpooh8GE2BJX1jxMxny3UBjIW3LwIb+PecWKeNB4B0goheE6jf49xcrrpLxeakfo+0x6WRbRP275+vcYutbqEIgEHPwCZpkzTwZgOlWHP4d0YAll7B8dG+lU4VZ8amaYAMsH5FNSluggmu/MJK+Icz2yOf1ogRivrnFbz6so+3t/3pKqsR9I76b0pabuMWslfF7H4BIrjxfm3K5g39PJh2DcMMiKaCu5k+MA8ZMUFN7wgUh5dBh4kVKus7x6VwIDAQAB";
  private static final String[] GOOGLE_CATALOG = new String[]{
    "radio_upnp.donation.1",
    "radio_upnp.donation.2",
    "radio_upnp.donation.3"
  };
  // http://developer.android.com/google/play/billing/billing_testing.html
  private static final String[] DEBUG_CATALOG = new String[]{
    "android.test.purchased",
    "android.test.canceled",
    "android.test.item_unavailable"};
  // <HMI assets
  private Spinner googleSpinner;
  // />
  // Google Play helper object
  private IabHelper iabHelper;
  // Callback for when a purchase is finished
  private final IabHelper.OnIabPurchaseFinishedListener purchaseFinishedListener =
    new IabHelper.OnIabPurchaseFinishedListener() {
      public void onIabPurchaseFinished(IabResult result, Purchase purchase) {
        Log.d(LOG_TAG, "Purchase finished: " + result + ", purchase: " + purchase);
        // If we were disposed of in the meantime, quit
        if (iabHelper == null) {
          return;
        }
        if (result.isSuccess()) {
          Log.d(LOG_TAG, "Purchase successful");
          // Directly consume in-app purchase, so that people can donate multiple times
          try {
            iabHelper.consumeAsync(
              purchase,
              new IabHelper.OnConsumeFinishedListener() {
                public void onConsumeFinished(Purchase purchase, IabResult result) {
                  Log.d(LOG_TAG,
                    "Consumption finished. Purchase: " + purchase + ", result: " + result);
                  // If we were disposed of in the meantime, quit
                  if (iabHelper == null) {
                    return;
                  }
                  if (result.isSuccess()) {
                    Log.d(LOG_TAG, "Consumption successful. Provisioning");
                    // Show thanks openDialog
                    openDialog(
                      android.R.drawable.ic_dialog_info,
                      R.string.donation_thanks_dialog_title,
                      Objects.requireNonNull(getActivity())
                        .getResources().getString(R.string.donation_thanks_dialog));
                  } else {
                    complaign(result.getMessage());
                  }
                  Log.d(LOG_TAG, "End consumption flow");
                }
              });
          } catch (IabHelper.IabAsyncInProgressException iabAsyncInProgressException) {
            complaign(Objects.requireNonNull(getActivity())
              .getResources().getString(R.string.donation_alert_dialog_try_again));
          }
        } else {
          // No error message for user cancel
          if ((result.getResponse() != IABHELPER_USER_CANCELLED) &&
            (result.getResponse() != BILLING_RESPONSE_RESULT_USER_CANCELED)) {
            complaign(result.getMessage());
          }
        }
      }
    };
  // Callback for when a purchase is finished
  private final IabHelper.OnIabSetupFinishedListener setupFinishedListener =
    new IabHelper.OnIabSetupFinishedListener() {
      public void onIabSetupFinished(IabResult result) {
        Log.d(LOG_TAG, "Setup finished");
        // Have we been disposed of in the meantime? If so, quit
        if (iabHelper == null) {
          return;
        }
        if (!result.isSuccess()) {
          complaign(result.getMessage());
        }
      }
    };

  @Override
  public void onActivityResult(int requestCode, int resultCode, Intent data) {
    Log.d(LOG_TAG, "onActivityResult(" + requestCode + "," + resultCode + "," + data + ")");
    // iabHelper may be null if disposed in the meantime
    if ((iabHelper != null) && iabHelper.handleActivityResult(requestCode, resultCode, data)) {
      Log.d(LOG_TAG, "onActivityResult handled by IABUtil");
    } else {
      // Not handeld here, we pass through
      super.onActivityResult(requestCode, resultCode, data);
    }
  }

  @Nullable
  @Override
  public View onCreateView(
    @NonNull LayoutInflater inflater, @Nullable ViewGroup container, Bundle savedInstanceState) {
    super.onCreateView(inflater, container, savedInstanceState);
    final View view = inflater.inflate(R.layout.content_donation, container, false);
    // Choose donation amount
    googleSpinner = view.findViewById(R.id.donation_google_android_market_spinner);
    return view;
  }

  @Override
  public void onActivityCreated(@Nullable Bundle savedInstanceState) {
    super.onActivityCreated(savedInstanceState);
    // Adapters
    ArrayAdapter<CharSequence> donationAdapter = new ArrayAdapter<CharSequence>(
      Objects.requireNonNull(getActivity()),
      android.R.layout.simple_spinner_item,
      BuildConfig.DEBUG ?
        DEBUG_CATALOG : getResources().getStringArray(R.array.donation_google_catalog_values));
    donationAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    googleSpinner.setAdapter(donationAdapter);
    // Create the helper, passing it our context and the public key to verify signatures with
    iabHelper = new IabHelper(getActivity(), PUBKEY);
    // Enable debug logging (for a production application, you should set this to false)
    iabHelper.enableDebugLogging(BuildConfig.DEBUG);
    // Start setup. This is asynchronous and the specified listener
    // will be called once setup completes.
    Log.d(LOG_TAG, "Starting setup");
    iabHelper.startSetup(setupFinishedListener);
  }

  @NonNull
  @Override
  public View.OnClickListener getFloatingActionButtonOnClickListener() {
    return new View.OnClickListener() {

      @Override
      public void onClick(View view) {
        int index = googleSpinner.getSelectedItemPosition();
        Log.d(LOG_TAG, "selected item in spinner: " + index);
        // When debugging, choose android.test.x item
        try {
          iabHelper.launchPurchaseFlow(
            getActivity(),
            BuildConfig.DEBUG ? DEBUG_CATALOG[index] : GOOGLE_CATALOG[index],
            0,
            purchaseFinishedListener);
        } catch (IabHelper.IabAsyncInProgressException iabAsyncInProgressException) {
          // In some devices, it is impossible to setup IAB Helper
          // and this exception is thrown, being almost "impossible"
          // to the user to control it and forcing app close
          Log.e(LOG_TAG, iabAsyncInProgressException.getMessage());
          openDialog(
            android.R.drawable.ic_dialog_alert,
            R.string.donation_google_android_market_not_supported_title,
            Objects.requireNonNull(getActivity())
              .getResources()
              .getString(R.string.donation_google_android_market_not_supported));
        }
      }
    };
  }

  @Override
  public int getFloatingActionButtonResource() {
    return R.drawable.ic_payment_black_24dp;
  }

  @Override
  public int getTitle() {
    return R.string.title_donate;
  }

  private void openDialog(int icon, int title, @NonNull String message) {
    new AlertDialog.Builder(getActivity())
      .setIcon(icon)
      .setTitle(title)
      .setMessage(message)
      .setCancelable(true)
      .setNeutralButton(
        R.string.donation_button_close,
        new DialogInterface.OnClickListener() {
          @Override
          public void onClick(DialogInterface dialog, int which) {
            dialog.dismiss();
          }
        }
      )
      .show();
  }

  private void complaign(@NonNull String message) {
    openDialog(
      android.R.drawable.ic_dialog_alert,
      R.string.donation_alert_dialog_title,
      Objects.requireNonNull(getActivity())
        .getResources().getString(R.string.donation_alert_tip) + message);
  }
}