package org.h2.engine;

import java.util.concurrent.atomic.AtomicBoolean;

public class CompactStatus {
  public volatile long lastCompact = 0;
  public final AtomicBoolean compacting = new AtomicBoolean(false);

}
