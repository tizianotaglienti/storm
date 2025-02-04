/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.  The ASF licenses this file to you under the Apache License, Version
 * 2.0 (the "License"); you may not use this file except in compliance with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */

package org.apache.storm;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.security.auth.Subject;
import org.apache.commons.collections.CollectionUtils;
import org.apache.storm.cluster.DaemonType;
import org.apache.storm.generated.AuthorizationException;
import org.apache.storm.generated.KeyAlreadyExistsException;
import org.apache.storm.generated.KeyNotFoundException;
import org.apache.storm.generated.ReadableBlobMeta;
import org.apache.storm.nimbus.NimbusInfo;
import org.apache.storm.security.auth.NimbusPrincipal;
import org.apache.storm.shade.org.apache.curator.framework.CuratorFramework;
import org.apache.storm.shade.org.apache.zookeeper.KeeperException;
import org.apache.storm.thrift.transport.TTransportException;
import org.apache.storm.utils.CuratorUtils;
import org.apache.storm.utils.NimbusClient;
import org.apache.storm.utils.WrappedKeyNotFoundException;
import org.apache.storm.utils.ZookeeperAuthInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BlobStoreUtils {
    private static final String BLOBSTORE_SUBTREE = "/blobstore";

    private static final Logger LOG = LoggerFactory.getLogger(BlobStoreUtils.class);

    public static String getBlobStoreSubtree() {
        return BLOBSTORE_SUBTREE;
    }

    @SuppressWarnings("checkstyle:AbbreviationAsWordInName")
    public static CuratorFramework createZKClient(Map<String, Object> conf, DaemonType type) {
        @SuppressWarnings("unchecked")
        List<String> zkServers = (List<String>) conf.get(Config.STORM_ZOOKEEPER_SERVERS);
        Object port = conf.get(Config.STORM_ZOOKEEPER_PORT);
        ZookeeperAuthInfo zkAuthInfo = new ZookeeperAuthInfo(conf);
        CuratorFramework zkClient = CuratorUtils.newCurator(conf, zkServers, port,
                                                            (String) conf.get(Config.STORM_ZOOKEEPER_ROOT), zkAuthInfo,
                                                            type.getDefaultZkAcls(conf));
        zkClient.start();
        return zkClient;
    }

    public static Subject getNimbusSubject() {
        Subject subject = new Subject();
        subject.getPrincipals().add(new NimbusPrincipal());
        return subject;
    }

    // Normalize state
    public static BlobKeySequenceInfo normalizeNimbusHostPortSequenceNumberInfo(String nimbusSeqNumberInfo) {
        BlobKeySequenceInfo keySequenceInfo = new BlobKeySequenceInfo();
        int lastIndex = nimbusSeqNumberInfo.lastIndexOf("-");
        keySequenceInfo.setNimbusHostPort(nimbusSeqNumberInfo.substring(0, lastIndex));
        keySequenceInfo.setSequenceNumber(nimbusSeqNumberInfo.substring(lastIndex + 1));
        return keySequenceInfo;
    }

    // Check for latest sequence number of a key inside zookeeper and return nimbodes containing the latest sequence number
    public static Set<NimbusInfo> getNimbodesWithLatestSequenceNumberOfBlob(CuratorFramework zkClient, String key) throws Exception {
        List<String> stateInfoList;
        try {
            stateInfoList = zkClient.getChildren().forPath("/blobstore/" + key);
        } catch (KeeperException.NoNodeException e) {
            // there's a race condition with a delete: blobstore
            // this should be thrown to the caller to indicate that the key is invalid now
            throw new WrappedKeyNotFoundException(key);
        }

        Set<NimbusInfo> nimbusInfoSet = new HashSet<NimbusInfo>();
        int latestSeqNumber = getLatestSequenceNumber(stateInfoList);
        LOG.debug("getNimbodesWithLatestSequenceNumberOfBlob stateInfo {} version {}", stateInfoList, latestSeqNumber);
        // Get the nimbodes with the latest version
        for (String state : stateInfoList) {
            BlobKeySequenceInfo sequenceInfo = normalizeNimbusHostPortSequenceNumberInfo(state);
            if (latestSeqNumber == Integer.parseInt(sequenceInfo.getSequenceNumber())) {
                nimbusInfoSet.add(NimbusInfo.parse(sequenceInfo.getNimbusHostPort()));
            }
        }
        LOG.debug("nimbusInfoList {}", nimbusInfoSet);
        return nimbusInfoSet;
    }

    // Get sequence number details from latest sequence number of the blob
    public static int getLatestSequenceNumber(List<String> stateInfoList) {
        int seqNumber = 0;
        // Get latest sequence number of the blob present in the zookeeper --> possible to refactor this piece of code
        for (String state : stateInfoList) {
            BlobKeySequenceInfo sequenceInfo = normalizeNimbusHostPortSequenceNumberInfo(state);
            int currentSeqNumber = Integer.parseInt(sequenceInfo.getSequenceNumber());
            if (seqNumber < currentSeqNumber) {
                seqNumber = currentSeqNumber;
                LOG.debug("Sequence Info {}", seqNumber);
            }
        }
        LOG.debug("Latest Sequence Number {}", seqNumber);
        return seqNumber;
    }

    // Download missing blobs from potential nimbodes
    public static boolean downloadMissingBlob(Map<String, Object> conf, BlobStore blobStore, String key, Set<NimbusInfo> nimbusInfos)
        throws TTransportException {
        ReadableBlobMeta rbm;
        ClientBlobStore remoteBlobStore;
        boolean isSuccess = false;
        LOG.debug("Download blob NimbusInfos {}", nimbusInfos);
        for (NimbusInfo nimbusInfo : nimbusInfos) {
            if (isSuccess) {
                break;
            }
            LOG.debug("Download blob key: {}, NimbusInfo {}", key, nimbusInfo);
            try (NimbusClient client = new NimbusClient(conf, nimbusInfo.getHost(), nimbusInfo.getPort(), null)) {
                rbm = client.getClient().getBlobMeta(key);
                remoteBlobStore = new NimbusBlobStore();
                remoteBlobStore.setClient(conf, client);
                try (InputStreamWithMeta in = remoteBlobStore.getBlob(key)) {
                    blobStore.createBlob(key, in, rbm.get_settable(), getNimbusSubject());
                }
                // if key already exists while creating the blob else update it
                Iterator<String> keyIterator = blobStore.listKeys();
                while (keyIterator.hasNext()) {
                    if (keyIterator.next().equals(key)) {
                        LOG.debug("Success creating key, {}", key);
                        isSuccess = true;
                        break;
                    }
                }
            } catch (IOException | AuthorizationException exception) {
                throw new RuntimeException(exception);
            } catch (KeyAlreadyExistsException kae) {
                LOG.info("KeyAlreadyExistsException Key: {} {}", key, kae);
            } catch (KeyNotFoundException knf) {
                // Catching and logging KeyNotFoundException because, if
                // there is a subsequent update and delete, the non-leader
                // nimbodes might throw an exception.
                LOG.info("KeyNotFoundException Key: {} {}", key, knf);
            } catch (Exception exp) {
                // Logging an exception while client is connecting
                LOG.error("Exception {}", exp);
            }
        }

        if (!isSuccess) {
            LOG.error("Could not download the blob with key: {}", key);
        }
        return isSuccess;
    }

    // Download updated blobs from potential nimbodes
    public static boolean downloadUpdatedBlob(Map<String, Object> conf, BlobStore blobStore, String key, Set<NimbusInfo> nimbusInfos)
        throws TTransportException {
        ClientBlobStore remoteBlobStore;
        AtomicOutputStream out = null;
        boolean isSuccess = false;
        LOG.debug("Download blob NimbusInfos {}", nimbusInfos);
        for (NimbusInfo nimbusInfo : nimbusInfos) {
            if (isSuccess) {
                break;
            }
            try (NimbusClient client = new NimbusClient(conf, nimbusInfo.getHost(), nimbusInfo.getPort(), null)) {
                remoteBlobStore = new NimbusBlobStore();
                remoteBlobStore.setClient(conf, client);
                try (InputStreamWithMeta in = remoteBlobStore.getBlob(key)) {
                    out = blobStore.updateBlob(key, getNimbusSubject());
                    byte[] buffer = new byte[2048];
                    int len = 0;
                    while ((len = in.read(buffer)) > 0) {
                        out.write(buffer, 0, len);
                    }
                    out.close();
                    out = null;
                }
                isSuccess = true;
            } catch (FileNotFoundException fnf) {
                LOG.warn("Blobstore file for key '{}' does not exist or got deleted before it could be downloaded.", key, fnf);
            } catch (IOException | AuthorizationException exception) {
                throw new RuntimeException(exception);
            } catch (KeyNotFoundException knf) {
                // Catching and logging KeyNotFoundException because, if
                // there is a subsequent update and delete, the non-leader
                // nimbodes might throw an exception.
                LOG.info("KeyNotFoundException", knf);
            } catch (Exception exp) {
                // Logging an exception while client is connecting
                LOG.error("Exception", exp);
            } finally {
                if (out != null) {
                    try {
                        out.cancel();
                    } catch (IOException e) {
                        // Ignore.
                    }
                }
            }
        }

        if (!isSuccess) {
            LOG.error("Could not update the blob with key: {}", key);
        }
        return isSuccess;
    }

    // Get the list of keys from blobstore
    public static List<String> getKeyListFromBlobStore(BlobStore blobStore) throws Exception {
        Iterator<String> keys = blobStore.listKeys();
        List<String> keyList = new ArrayList<String>();
        if (keys != null) {
            while (keys.hasNext()) {
                keyList.add(keys.next());
            }
        }
        LOG.debug("KeyList from blobstore {}", keyList);
        return keyList;
    }

    public static void createStateInZookeeper(Map<String, Object> conf, String key, NimbusInfo nimbusInfo) throws TTransportException {
        ClientBlobStore cb = new NimbusBlobStore();
        cb.setClient(conf, new NimbusClient(conf, nimbusInfo.getHost(), nimbusInfo.getPort(), null));
        cb.createStateInZookeeper(key);
    }

    public static void updateKeyForBlobStore(Map<String, Object> conf, BlobStore blobStore, CuratorFramework zkClient, String key,
                                             NimbusInfo nimbusDetails) {
        try {
            // Most of clojure tests currently try to access the blobs using getBlob. Since, updateKeyForBlobStore
            // checks for updating the correct version of the blob as a part of nimbus ha before performing any
            // operation on it, there is a necessity to stub several test cases to ignore this method. It is a valid
            // trade off to return if nimbusDetails which include the details of the current nimbus host port data are
            // not initialized as a part of the test. Moreover, this applies to only local blobstore when used along with
            // nimbus ha.
            if (nimbusDetails == null) {
                return;
            }
            boolean isListContainsCurrentNimbusInfo = false;
            List<String> stateInfo;
            if (zkClient.checkExists().forPath(BLOBSTORE_SUBTREE + "/" + key) == null) {
                return;
            }
            stateInfo = zkClient.getChildren().forPath(BLOBSTORE_SUBTREE + "/" + key);
            if (CollectionUtils.isEmpty(stateInfo)) {
                return;
            }

            LOG.debug("StateInfo for update {}", stateInfo);
            Set<NimbusInfo> nimbusInfoList = getNimbodesWithLatestSequenceNumberOfBlob(zkClient, key);

            for (NimbusInfo nimbusInfo : nimbusInfoList) {
                if (nimbusInfo.getHost().equals(nimbusDetails.getHost())) {
                    isListContainsCurrentNimbusInfo = true;
                    break;
                }
            }

            if (!isListContainsCurrentNimbusInfo && downloadUpdatedBlob(conf, blobStore, key, nimbusInfoList)) {
                LOG.debug("Updating state inside zookeeper for an update");
                createStateInZookeeper(conf, key, nimbusDetails);
            }
        } catch (KeeperException.NoNodeException | KeyNotFoundException e) {
            //race condition with a delete
            return;
        } catch (Exception exp) {
            throw new RuntimeException(exp);
        }
    }
}
