package io.nekohasekai.sagernet.aidl;

import io.nekohasekai.sagernet.aidl.ICoreTestCallback;

interface ICoreService {
  void urlTest(in long[] profileIds, String url, int timeoutMs, int concurrency, ICoreTestCallback cb);
  void speedTest(long profileId, String mode, int timeoutMs, String simpleDownloadUrl, ICoreTestCallback cb);
  void stopTests();
}
