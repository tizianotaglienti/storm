/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.storm.hdfs.blobstore;

import java.io.IOException;
import java.util.Iterator;
import java.util.Map;

import org.apache.storm.AtomicOutputStream;
import org.apache.storm.ClientBlobStore;
import org.apache.storm.InputStreamWithMeta;
import org.apache.storm.generated.AuthorizationException;
import org.apache.storm.generated.KeyAlreadyExistsException;
import org.apache.storm.generated.KeyNotFoundException;
import org.apache.storm.generated.ReadableBlobMeta;
import org.apache.storm.generated.SettableBlobMeta;
import org.apache.storm.utils.NimbusClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client to access the HDFS blobStore. At this point, this is meant to only be used by the
 * supervisor.  Don't trust who the client says they are so pass null for all Subjects.
 *
 * <p>The HdfsBlobStore implementation takes care of the null Subjects. It assigns Subjects
 * based on what hadoop says who the users are. These users must be configured accordingly
 * in the SUPERVISOR_ADMINS for ACL validation and for the supervisors to download the blobs.
 * This API is only used by the supervisor in order to talk directly to HDFS.
 */
public class HdfsClientBlobStore extends ClientBlobStore {
    private static final Logger LOG = LoggerFactory.getLogger(HdfsClientBlobStore.class);
    private HdfsBlobStore blobStore;
    private Map conf;
    private NimbusClient client;

    @Override
    public void prepare(Map<String, Object> conf) {
        this.conf = conf;
        blobStore = new HdfsBlobStore();
        blobStore.prepare(conf, null, null, null);
    }

    @Override
    public AtomicOutputStream createBlobToExtend(String key, SettableBlobMeta meta)
            throws AuthorizationException, KeyAlreadyExistsException {
        return blobStore.createBlob(key, meta, null);
    }

    @Override
    public AtomicOutputStream updateBlob(String key)
            throws AuthorizationException, KeyNotFoundException {
        return blobStore.updateBlob(key, null);
    }

    @Override
    public ReadableBlobMeta getBlobMeta(String key)
            throws AuthorizationException, KeyNotFoundException {
        return blobStore.getBlobMeta(key, null);
    }

    @Override
    public boolean isRemoteBlobExists(String blobKey) throws AuthorizationException {
        return blobStore.blobExists(blobKey, null);
    }

    @Override
    public void setBlobMetaToExtend(String key, SettableBlobMeta meta)
            throws AuthorizationException, KeyNotFoundException {
        blobStore.setBlobMeta(key, meta, null);
    }

    @Override
    public void deleteBlob(String key) throws AuthorizationException, KeyNotFoundException {
        blobStore.deleteBlob(key, null);
    }

    @Override
    public InputStreamWithMeta getBlob(String key)
            throws AuthorizationException, KeyNotFoundException {
        return blobStore.getBlob(key, null);
    }

    @Override
    public Iterator<String> listKeys() {
        return blobStore.listKeys();
    }

    @Override
    public int getBlobReplication(String key) throws AuthorizationException, KeyNotFoundException {
        return blobStore.getBlobReplication(key, null);
    }

    @Override
    public int updateBlobReplication(String key, int replication) throws AuthorizationException, KeyNotFoundException {
        return blobStore.updateBlobReplication(key, replication, null);
    }

    @Override
    public boolean setClient(Map<String, Object> conf, NimbusClient client) {
        this.client = client;
        return true;
    }

    @Override
    public void createStateInZookeeper(String key) {
        // Do nothing
    }

    @Override
    public void shutdown() {
        close();
    }

    @Override
    public void close() {
        if (client != null) {
            client.close();
            client = null;
        }
    }

    @Override
    public long getRemoteBlobstoreUpdateTime() throws IOException {
        return blobStore.getLastBlobUpdateTime();
    }
}
