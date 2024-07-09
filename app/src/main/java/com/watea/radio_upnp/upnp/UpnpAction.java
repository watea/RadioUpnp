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

package com.watea.radio_upnp.upnp;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.ksoap2.serialization.PropertyInfo;
import org.ksoap2.serialization.SoapPrimitive;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Vector;

public abstract class UpnpAction {
  private final String LOG_TAG = UpnpAction.class.getName();
  @NonNull
  private final Action action;
  @NonNull
  private final ActionController actionController;
  private final List<String[]> arguments = new Vector<>();
  private final Set<PropertyInfo> propertyInfos = new HashSet<>();

  public UpnpAction(
    @NonNull Action action,
    @NonNull ActionController actionController) {
    this.action = action;
    this.actionController = actionController;
  }

  public UpnpAction(
    @NonNull Action action,
    @NonNull ActionController actionController,
    @NonNull String instanceId) {
    this(action, actionController);
    addArgument("InstanceId", instanceId);
  }

  @Nullable
  public SoapPrimitive getPropertyInfo(@NonNull String Name) {
    return propertyInfos.stream().
      filter(propertyInfo ->
        propertyInfo.getName().equals(Name) && propertyInfo.getValue() instanceof SoapPrimitive)
      .findFirst()
      .map(propertyInfo -> (SoapPrimitive) propertyInfo.getValue())
      .orElse(null);
  }

  public UpnpAction addArgument(@NonNull String name, @NonNull String value) {
    arguments.add(new String[]{name, value});
    return this;
  }

  public void execute(boolean isOnOwnThread) {
    Log.d(
      LOG_TAG, "execute: " + action.getName() + " on: " + action.getDevice().getDisplayString());
    final Request request = new Request(action.getService(), action.getName(), arguments) {
      @Override
      public void onSuccess(@NonNull Set<PropertyInfo> result) {
        Log.d(LOG_TAG, "Successfully called UPnP action: " + action.getName());
        propertyInfos.addAll(result);
        UpnpAction.this.onSuccess();
      }

      @Override
      public void onFailure(@NonNull String faultCode, @NonNull String faultString) {
        Log.d(LOG_TAG, "UPnP error: " + action.getName() + "/" + faultCode + "/" + faultString);
        UpnpAction.this.onFailure();
      }
    };
    if (isOnOwnThread) {
      new Thread(request::call).start();
    } else {
      request.call();
    }
  }

  public void schedule() {
    actionController.schedule(this);
  }

  public boolean hasDevice(@NonNull Device device) {
    return action.getDevice().equals(device);
  }

  // Run next by default
  protected void onSuccess() {
    actionController.runNextAction();
  }

  // Run next by default
  protected void onFailure() {
    actionController.runNextAction();
  }
}