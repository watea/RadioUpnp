package com.watea.radio_upnp.upnp;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

public class Action extends Asset {
  public static final String XML_NAME = "action";
  private static final String LOG_TAG = Action.class.getName();
  private final Service service;
  private final Set<Argument> arguments = new HashSet<>();
  private final AtomicReference<Argument> currentArgument = new AtomicReference<>();
  @Nullable
  private String name = null;

  public Action(@NonNull Service service) {
    this.service = service;
  }

  @Override
  public void startAccept(@NonNull URLService uRLService, @NonNull String currentTag) {
    // Process Argument, if any
    if (currentTag.equals(Argument.XML_NAME)) {
      currentArgument.set(new Argument());
    }
  }

  @Override
  public void endAccept(@NonNull URLService uRLService, @NonNull String currentTag) {
    final Argument argument = currentArgument.get();
    if (argument == null) {
      // Process Action field
      if (currentTag.equals("name")) {
        name = uRLService.getTag(currentTag);
        // No more tags for Action
        uRLService.clearTags();
      }
    } else {
      // Process Argument, if any
      argument.endAccept(uRLService, currentTag);
      if (currentTag.equals(Argument.XML_NAME)) {
        if (argument.isComplete()) {
          arguments.add(argument);
        } else {
          Log.d(LOG_TAG, "endAccept: try to add an incomplete Argument to " + name);
        }
        currentArgument.set(null);
      }
    }
  }

  @NonNull
  public Set<Argument> getArguments() {
    return arguments;
  }

  @NonNull
  public String getName() {
    assert name != null;
    return name;
  }

  public boolean hasName(@NonNull String name) {
    return name.equals(this.name);
  }

  @Override
  public boolean isComplete() {
    return (name != null);
  }

  @NonNull
  public Service getService() {
    return service;
  }

  @NonNull
  public Device getDevice() {
    return service.getDevice();
  }

  public static class Argument extends Asset {
    private static final String XML_NAME = "argument";
    private String name = null;
    private String direction = null;

    @Override
    public void endAccept(@NonNull URLService uRLService, @NonNull String currentTag) {
      if (currentTag.equals(XML_NAME)) {
        name = uRLService.getTag("name");
        direction = uRLService.getTag("direction");
        // No more tags for Argument
        uRLService.clearTags();
      }
    }

    public boolean isIn() {
      return (direction != null) && direction.equalsIgnoreCase("in");
    }

    @NonNull
    public String getName() {
      return name;
    }

    @Override
    public boolean isComplete() {
      return (name != null) && (direction != null);
    }
  }
}