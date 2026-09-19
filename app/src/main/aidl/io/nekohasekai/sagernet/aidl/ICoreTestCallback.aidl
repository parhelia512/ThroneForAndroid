package io.nekohasekai.sagernet.aidl;

import io.nekohasekai.sagernet.bg.proto.SpeedTestSnapshot;

oneway interface ICoreTestCallback {
  void onUrlTestResult(long profileId, int latencyMs, String error);
  void onSpeedTestProgress(in SpeedTestSnapshot snapshot);
  void onDone();
}
