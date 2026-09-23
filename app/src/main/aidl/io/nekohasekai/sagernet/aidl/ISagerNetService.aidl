package io.nekohasekai.sagernet.aidl;

import io.nekohasekai.sagernet.aidl.ISagerNetServiceCallback;

interface ISagerNetService {
  int getState();
  String getProfileName();

  void registerCallback(in ISagerNetServiceCallback cb, int id);
  oneway void unregisterCallback(in ISagerNetServiceCallback cb);
  oneway void resetTraffic(in long[] profileIds);

  int urlTest();

  // Refreshes the running instance's remote rule-sets; blocks up to about 60 s. Returns {"updated":n,"error":"text"}.
  String updateRuleSets();
}
