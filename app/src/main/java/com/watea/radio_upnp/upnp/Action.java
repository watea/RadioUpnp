package com.watea.radio_upnp.upnp;

import android.util.Log;

import androidx.annotation.NonNull;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

public class Action implements Asset {
  public static final String XML_NAME = "action";
  private static final String LOG_TAG = Action.class.getName();
  private final Set<Argument> arguments = new HashSet<>();
  private String name;
  private final URLService.Consumer xMLBuilder = new URLService.Consumer() {
    private final AtomicReference<Argument> currentArgument = new AtomicReference<>();

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
        argument.getXMLBuilder().endAccept(uRLService, currentTag);
        if (currentTag.equals(Argument.XML_NAME)) {
          if (argument.isComplete()) {
            arguments.add(argument);
          } else {
            Log.d(LOG_TAG, "endAccept: try to add an incomplete Argument");
          }
          currentArgument.set(null);
        }
      }
    }
  };

  public Set<Argument> getArguments() {
    return arguments;
  }

  public String getName() {
    return name;
  }

  @Override
  public boolean isComplete() {
    return (name != null);
  }

  @NonNull
  @Override
  public URLService.Consumer getXMLBuilder() {
    return xMLBuilder;
  }

  public static class Argument implements Asset {
    private static final String XML_NAME = "argument";
    private String name = null;
    private String direction = null;
    private final URLService.Consumer xMLBuilder = new URLService.Consumer() {
      @Override
      public void endAccept(@NonNull URLService uRLService, @NonNull String currentTag) {
        if (currentTag.equals(XML_NAME)) {
          name = uRLService.getTag("name");
          direction = uRLService.getTag("direction");
          // No more tags for Argument
          uRLService.clearTags();
        }
      }
    };

    public boolean isIn() {
      return (direction != null) && direction.equalsIgnoreCase("in");
    }

    public String getName() {
      return name;
    }

    @Override
    public boolean isComplete() {
      return (name != null) && (direction != null);
    }

    @NonNull
    @Override
    public URLService.Consumer getXMLBuilder() {
      return xMLBuilder;
    }
  }
}