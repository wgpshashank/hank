/**
 *  Copyright 2011 Rapleaf
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.rapleaf.hank.storage;

import com.rapleaf.hank.coordinator.Domain;
import com.rapleaf.hank.coordinator.DomainVersion;
import com.rapleaf.hank.coordinator.mock.MockDomainVersion;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

public class MockIncrementalPartitionUpdater extends IncrementalPartitionUpdater {

  private final Domain domain;
  private final Integer currentVersion;
  private final Integer[] cachedVersions;

  public MockIncrementalPartitionUpdater(final String localPartitionRoot,
                                         final Domain domain,
                                         final Integer currentVersion,
                                         final Integer... cachedVersions) throws IOException {
    super(domain, localPartitionRoot);
    this.domain = domain;
    this.currentVersion = currentVersion;
    this.cachedVersions = cachedVersions;
  }

  @Override
  protected Set<DomainVersion> detectCachedVersionsCore() throws IOException {
    Set<DomainVersion> cachedVersionsSet = new HashSet<DomainVersion>();
    for (Integer versionNumber : cachedVersions) {
      cachedVersionsSet.add(new MockDomainVersion(versionNumber, 0l));
    }
    return cachedVersionsSet;
  }

  @Override
  protected void cleanCachedVersions() throws IOException {
  }

  @Override
  protected void fetchVersion(DomainVersion version, String fetchRoot) {
  }

  @Override
  protected Integer detectCurrentVersionNumber() throws IOException {
    return currentVersion;
  }

  @Override
  protected DomainVersion getParentDomainVersion(DomainVersion domainVersion) throws IOException {
    if (domainVersion.getVersionNumber() == 0) {
      return null;
    } else {
      return domain.getVersionByNumber(domainVersion.getVersionNumber() - 1);
    }
  }
}
