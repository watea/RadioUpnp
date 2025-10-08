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

import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.watea.radio_upnp.R;

public class OpenDonationFragment extends MainActivityFragment {
  private static final Uri LIBERAPAY_URI = Uri.parse("https://liberapay.com/watea/donate");
  private static final Uri PAYPAL_URI = Uri.parse("https://paypal.me/frwatea?country.x=FR&locale.x=fr_FR");

  // Send a mail for contact
  @NonNull
  @Override
  public View.OnClickListener getFloatingActionButtonOnClickListener() {
    return v -> startActivity(getMainActivity().getNewSendIntent());
  }

  @Override
  public int getFloatingActionButtonResource() {
    return R.drawable.ic_email_white_24dp;
  }

  @Override
  public int getTitle() {
    return R.string.title_donate;
  }

  @Override
  public void onCreateView(@NonNull View view, @Nullable ViewGroup container) {
    view.findViewById(R.id.liberapay_image_button).setOnClickListener(getLauncher(LIBERAPAY_URI));
    view.findViewById(R.id.paypal_image_button).setOnClickListener(getLauncher(PAYPAL_URI));
  }

  @Override
  protected int getLayout() {
    return R.layout.content_donation;
  }

  @NonNull
  private View.OnClickListener getLauncher(@NonNull Uri uri) {
    assert getActivity() != null;
    return (view -> getActivity().startActivity(new Intent(Intent.ACTION_VIEW, uri)));
  }
}