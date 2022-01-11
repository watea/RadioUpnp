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

package com.watea.radio_upnp.service;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.watea.radio_upnp.model.Radio;

import org.fourthline.cling.android.AndroidUpnpService;
import org.fourthline.cling.controlpoint.ActionCallback;
import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.model.meta.Action;
import org.fourthline.cling.model.meta.Device;

import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Vector;

public class UpnpActionController {
  @NonNull
  private final AndroidUpnpService androidUpnpService;
  private final Map<Radio, String> contentTypes = new Hashtable<>();
  private final Map<Device<?, ?, ?>, List<String>> protocolInfos = new Hashtable<>();
  private final List<UpnpAction> upnpActions = new Vector<>();

  public UpnpActionController(@NonNull AndroidUpnpService androidUpnpService) {
    this.androidUpnpService = androidUpnpService;
  }

  @Nullable
  public String getContentType(@NonNull Radio radio) {
    return contentTypes.get(radio);
  }

  @Nullable
  public List<String> getProtocolInfo(@NonNull Device<?, ?, ?> device) {
    return protocolInfos.get(device);
  }

  public void fetchContentType(@NonNull Radio radio) {
    String contentType = new RadioURL(radio.getURL()).getStreamContentType();
    if (contentType != null) {
      contentTypes.put(radio, contentType);
    }
  }

  public synchronized void release(boolean actionsOnly) {
    if (!actionsOnly) {
      contentTypes.clear();
      protocolInfos.clear();
    }
    upnpActions.clear();
  }

  // Remove remaining actions on device or all (device == null)
  private synchronized void releaseActions(@NonNull final Device<?, ?, ?> device) {
    Iterator<UpnpAction> iter = upnpActions.iterator();
    while (iter.hasNext()) {
      if (iter.next().getDevice().equals(device)) {
        iter.remove();
      }
    }
  }

  private synchronized void runNextAction() {
    if (!upnpActions.isEmpty()) {
      upnpActions.remove(0);
      pullAction();
    }
  }

  private synchronized void schedule(@NonNull final UpnpAction upnpAction) {
    upnpActions.add(upnpAction);
    // First action?
    if (upnpActions.size() == 1) {
      pullAction();
    }
  }

  private void putProtocolInfo(@NonNull Device<?, ?, ?> device, @NonNull List<String> list) {
    protocolInfos.put(device, list);
  }

  private void pullAction() {
    if (!upnpActions.isEmpty()) {
      upnpActions.get(0).execute();
    }
  }

  private void execute(@NonNull ActionCallback actionCallback) {
    androidUpnpService.getControlPoint().execute(actionCallback);
  }

  public static abstract class UpnpAction {
    private static final String LOG_TAG = UpnpAction.class.getName();
    @NonNull
    private final UpnpActionController upnpActionController;
    @NonNull
    private final Action<?> action;

    public UpnpAction(
      @NonNull UpnpActionController upnpActionController, @NonNull Action<?> action) {
      this.upnpActionController = upnpActionController;
      this.action = action;
    }

    public void execute() {
      Log.d(LOG_TAG, "Execute: " + action.getName() + " on: " + getDevice().getDisplayString());
      upnpActionController.execute(new ActionCallback(getActionInvocation()) {
        @Override
        public void success(ActionInvocation actionInvocation) {
          Log.d(LOG_TAG,
            "Successfully called UPnP action: " + actionInvocation.getAction().getName());
          UpnpAction.this.success(actionInvocation);
        }

        @Override
        public void failure(
          ActionInvocation actionInvocation, UpnpResponse operation, String defaultMsg) {
          Log.d(LOG_TAG,
            "UPnP error: " + actionInvocation.getAction().getName() + " => " + defaultMsg);
          UpnpAction.this.failure();
        }
      });
    }

    @NonNull
    public Device<?, ?, ?> getDevice() {
      return action.getService().getDevice();
    }

    public void putProtocolInfo(@NonNull List<String> list) {
      upnpActionController.putProtocolInfo(getDevice(), list);
    }

    public void schedule() {
      upnpActionController.schedule(this);
    }

    @NonNull
    protected ActionInvocation<?> getActionInvocation(@Nullable String instanceId) {
      ActionInvocation<?> actionInvocation = new ActionInvocation<>(action);
      if (instanceId != null) {
        actionInvocation.setInput("InstanceID", instanceId);
      }
      return actionInvocation;
    }

    protected abstract ActionInvocation<?> getActionInvocation();

    // Run next by default
    protected void success(@NonNull ActionInvocation<?> actionInvocation) {
      upnpActionController.runNextAction();
    }

    // Run next by default
    protected void failure() {
      upnpActionController.releaseActions(getDevice());
      upnpActionController.runNextAction();
    }
  }
}