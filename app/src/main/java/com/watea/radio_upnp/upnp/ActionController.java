package com.watea.radio_upnp.upnp;

import androidx.annotation.NonNull;

import java.util.List;
import java.util.Vector;

public class ActionController {
  private final List<UpnpAction> upnpActions = new Vector<>();

  public synchronized void release() {
    upnpActions.clear();
  }

  public synchronized void release(@NonNull Device device) {
    upnpActions.removeIf(upnpAction -> upnpAction.hasDevice(device));
  }

  public synchronized void runNextAction() {
    if (!upnpActions.isEmpty()) {
      upnpActions.remove(0);
      pullAction(false);
    }
  }

  public synchronized void schedule(@NonNull UpnpAction upnpAction) {
    upnpActions.add(upnpAction);
    // First action? => Start new thread
    if (upnpActions.size() == 1) {
      pullAction(true);
    }
  }

  private void pullAction(boolean isOnOwnThread) {
    if (!upnpActions.isEmpty()) {
      upnpActions.get(0).execute(isOnOwnThread);
    }
  }
}