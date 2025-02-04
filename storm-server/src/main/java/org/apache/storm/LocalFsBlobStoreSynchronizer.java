/**
 * Licensed to the Apache Software Foundation (ASF)
 * under one or more contributor license agreements.
 * ee the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version
 * 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.
 * See the License for the specific language governing permissions
 * and limitations under the License.
 */

package org.apache.storm;

import java.nio.channels.ClosedByInterruptException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.apache.storm.generated.KeyNotFoundException;
import org.apache.storm.nimbus.NimbusInfo;
import org.apache.storm.shade.org.apache.curator.framework.CuratorFramework;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Is called periodically and updates the nimbus
 * with blobs based on the state stored inside the zookeeper
 * for a non leader nimbus trying to be in sync
 * with the operations performed on the leader nimbus.
 */
public class LocalFsBlobStoreSynchronizer {
    private static final Logger LOG = LoggerFactory.getLogger(LocalFsBlobStoreSynchronizer.class);
    private CuratorFramework zkClient;
    private Map<String, Object> conf;
    private BlobStore blobStore;
    private Set<String> blobStoreKeySet = new HashSet<String>();
    private Set<String> zookeeperKeySet = new HashSet<String>();
    private NimbusInfo nimbusInfo;

    public LocalFsBlobStoreSynchronizer(BlobStore blobStore, Map<String, Object> conf) {
        this.blobStore = blobStore;
        this.conf = conf;
    }

    public void setNimbusInfo(NimbusInfo nimbusInfo) {
        this.nimbusInfo = nimbusInfo;
    }

    public void setZkClient(CuratorFramework zkClient) {
        this.zkClient = zkClient;
    }

    public Set<String> getBlobStoreKeySet() {
        Set<String> keySet = new HashSet<String>();
        keySet.addAll(blobStoreKeySet);
        return keySet;
    }

    public void setBlobStoreKeySet(Set<String> blobStoreKeySet) {
        this.blobStoreKeySet = blobStoreKeySet;
    }

    public Set<String> getZookeeperKeySet() {
        Set<String> keySet = new HashSet<String>();
        keySet.addAll(zookeeperKeySet);
        return keySet;
    }

    public void setZookeeperKeySet(Set<String> zookeeperKeySet) {
        this.zookeeperKeySet = zookeeperKeySet;
    }

    public synchronized void syncBlobs() {
        try {
            LOG.debug("Sync blobs - blobstore keys {}, zookeeper keys {}", getBlobStoreKeySet(), getZookeeperKeySet());
            deleteKeySetFromBlobStoreNotOnZookeeper(getBlobStoreKeySet(), getZookeeperKeySet());
            updateKeySetForBlobStore(getBlobStoreKeySet());
            Set<String> keySetToDownload = getKeySetToDownload(getBlobStoreKeySet(), getZookeeperKeySet());
            LOG.debug("Key set Blobstore-> Zookeeper-> DownloadSet {}-> {}-> {}", getBlobStoreKeySet(), getZookeeperKeySet(),
                      keySetToDownload);

            for (String key : keySetToDownload) {
                try {
                    Set<NimbusInfo> nimbusInfoSet = BlobStoreUtils.getNimbodesWithLatestSequenceNumberOfBlob(zkClient, key);
                    // Removing self so as not to create a deadlock where a nimbus is trying to download a missing blob
                    // from itself
                    nimbusInfoSet.remove(this.nimbusInfo);
                    LOG.debug("syncBlobs, key: {}, nimbusInfoSet: {}", key, nimbusInfoSet);
                    if (BlobStoreUtils.downloadMissingBlob(conf, blobStore, key, nimbusInfoSet)) {
                        BlobStoreUtils.createStateInZookeeper(conf, key, nimbusInfo);
                    }
                } catch (KeyNotFoundException e) {
                    LOG.debug("Detected deletion for the key {} while downloading - skipping download", key);
                }
            }
        } catch (InterruptedException | ClosedByInterruptException exp) {
            LOG.error("Interrupt Exception {}", exp);
        } catch (Exception exp) {
            throw new RuntimeException(exp);
        }
    }

    public void deleteKeySetFromBlobStoreNotOnZookeeper(Set<String> keySetBlobStore, Set<String> keySetZookeeper) throws Exception {
        if (keySetBlobStore.removeAll(keySetZookeeper)
            || (keySetZookeeper.isEmpty() && !keySetBlobStore.isEmpty())) {
            LOG.debug("Key set to delete in blobstore {}", keySetBlobStore);
            for (String key : keySetBlobStore) {
                blobStore.deleteBlob(key, BlobStoreUtils.getNimbusSubject());
            }
        }
    }

    // Update current key list inside the blobstore if the version changes
    public void updateKeySetForBlobStore(Set<String> keySetBlobStore) {
        try {
            for (String key : keySetBlobStore) {
                LOG.debug("updating blob");
                BlobStoreUtils.updateKeyForBlobStore(conf, blobStore, zkClient, key, nimbusInfo);
            }
        } catch (Exception exp) {
            throw new RuntimeException(exp);
        }
    }

    // Make a key list to download
    public Set<String> getKeySetToDownload(Set<String> blobStoreKeySet, Set<String> zookeeperKeySet) {
        zookeeperKeySet.removeAll(blobStoreKeySet);
        LOG.debug("Key list to download {}", zookeeperKeySet);
        return zookeeperKeySet;
    }
}
