package io.throneproj.throne.aidl;

import io.throneproj.throne.aidl.SpeedDisplayData;
import io.throneproj.throne.aidl.TrafficDataBatch;

oneway interface ISagerNetServiceCallback {
  void stateChanged(int state, String profileName, String msg);
  void missingPlugin(String profileName, String pluginName);
  void cbSpeedUpdate(in SpeedDisplayData stats);
  void cbTrafficUpdate(in TrafficDataBatch stats);
  void cbSelectorUpdate(long id);
}
