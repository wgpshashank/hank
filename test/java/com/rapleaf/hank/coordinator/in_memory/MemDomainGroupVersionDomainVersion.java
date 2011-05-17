package com.rapleaf.hank.coordinator.in_memory;

import com.rapleaf.hank.coordinator.DomainConfig;
import com.rapleaf.hank.coordinator.DomainGroupVersionDomainVersion;

public class MemDomainGroupVersionDomainVersion implements DomainGroupVersionDomainVersion {
  private final DomainConfig domainConfig;
  private final Integer ver;

  public MemDomainGroupVersionDomainVersion(DomainConfig byName, Integer value) {
    this.domainConfig = byName;
    this.ver = value;
  }

  @Override
  public DomainConfig getDomainConfig() {
    return domainConfig;
  }

  @Override
  public int getVersionNumber() {
    return ver;
  }
}