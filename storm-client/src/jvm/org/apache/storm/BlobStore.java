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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import javax.security.auth.Subject;
import org.apache.storm.daemon.Shutdownable;
import org.apache.storm.generated.AuthorizationException;
import org.apache.storm.generated.KeyAlreadyExistsException;
import org.apache.storm.generated.KeyNotFoundException;
import org.apache.storm.generated.ReadableBlobMeta;
import org.apache.storm.generated.SettableBlobMeta;
import org.apache.storm.nimbus.ILeaderElector;
import org.apache.storm.nimbus.NimbusInfo;
import org.apache.storm.utils.ConfigUtils;
import org.apache.storm.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provides a way to store blobs that can be downloaded. Blobs must be able to be uploaded and listed from Nimbus, and
 * downloaded from the Supervisors. It is a key value based store. Key being a string and value being the blob data.
 *
 * <p>ACL checking must take place against the provided subject. If the blob store does not support Security it must
 * validate that all ACLs set are always WORLD, everything.
 *
 * <p>The users can upload their blobs through the blob store command line. The command line also allows us to update
 * and delete blobs.
 *
 * <p>Modifying the replication factor only works for HdfsBlobStore as for the LocalFsBlobStore the replication is
 * dependent on the number of Nimbodes available.
 */
public abstract class BlobStore implements Shutdownable, AutoCloseable {
    protected static final String BASE_BLOBS_DIR_NAME = "blobs";
    private static final Logger LOG = LoggerFactory.getLogger(BlobStore.class);
    private static final KeyFilter<String> TO_TOPO_ID = (key) -> ConfigUtils.getIdFromBlobKey(key);

    /**
     * Validates key checking for potentially harmful patterns.
     *
     * @param key Key for the blob
     */
    public static final void validateKey(String key) throws IllegalArgumentException {
        if (!Utils.isValidKey(key)) {
            throw new IllegalArgumentException(key + " does not appear to be a valid blob key");
        }
    }

    /**
     * Allows us to initialize the blob store.
     *
     * @param conf       The storm configuration
     * @param baseDir    The directory path to store the blobs
     * @param nimbusInfo Contains the nimbus host, port and leadership information
     */
    public abstract void prepare(Map<String, Object> conf, String baseDir, NimbusInfo nimbusInfo, ILeaderElector leaderElector);

    /**
     * Start the syncing blobs between the local running instance of the BlobStore and others.
     * A no-op for the HdfsBlobStore where HDFS itself does the syncing
     * but for the LocalFsBlobStore ZK state updates are run periodically here.
     */
    public void startSyncBlobs() throws KeyNotFoundException, AuthorizationException {
        // NO-OP by default
    }

    /**
     * Creates the blob.
     *
     * @param key  Key for the blob
     * @param meta Metadata which contains the acls information
     * @param who  Is the subject creating the blob
     * @return AtomicOutputStream returns a stream into which the data can be written
     */
    public abstract AtomicOutputStream createBlob(String key, SettableBlobMeta meta, Subject who) throws AuthorizationException,
        KeyAlreadyExistsException;

    /**
     * Wrapper called to create the blob which contains the byte data.
     *
     * @param key  Key for the blob
     * @param data Byte data that needs to be uploaded
     * @param meta Metadata which contains the acls information
     * @param who  Is the subject creating the blob
     */
    public void createBlob(String key, byte[] data, SettableBlobMeta meta, Subject who) throws AuthorizationException,
            KeyAlreadyExistsException, IOException {
        AtomicOutputStream out = null;
        try {
            out = createBlob(key, meta, who);
            out.write(data);
            out.close();
            out = null;
        } finally {
            if (out != null) {
                out.cancel();
            }
        }
    }

    /**
     * Wrapper called to create the blob which contains the byte data.
     *
     * @param key  Key for the blob
     * @param in   InputStream from which the data is read to be written as a part of the blob
     * @param meta Metadata which contains the acls information
     * @param who  Is the subject creating the blob
     */
    public void createBlob(String key, InputStream in, SettableBlobMeta meta, Subject who) throws AuthorizationException,
            KeyAlreadyExistsException, IOException {
        AtomicOutputStream out = null;
        try {
            out = createBlob(key, meta, who);
            byte[] buffer = new byte[2048];
            int len = 0;
            while ((len = in.read(buffer)) > 0) {
                out.write(buffer, 0, len);
            }
            out.close();
            out = null;
        } finally {
            try {
                if (out != null) {
                    out.cancel();
                }
                in.close();
            } catch (IOException throwaway) {
                // Ignored
            }
        }
    }

    /**
     * Updates the blob data.
     *
     * @param key Key for the blob
     * @param who Is the subject having the write privilege for the blob
     * @return AtomicOutputStream returns a stream into which the data can be written
     */
    public abstract AtomicOutputStream updateBlob(String key, Subject who) throws AuthorizationException, KeyNotFoundException;

    /**
     * Wrapper called to create the blob which contains the byte data.
     *
     * @param key  Key for the blob
     * @param data Byte data that needs to be uploaded
     * @param who  Is the subject creating the blob
     */
    public void updateBlob(String key, byte[] data, Subject who) throws AuthorizationException, IOException, KeyNotFoundException {
        AtomicOutputStream out = null;
        try {
            out = updateBlob(key, who);
            out.write(data);
            out.close();
            out = null;
        } finally {
            if (out != null) {
                out.cancel();
            }
        }
    }

    /**
     * Gets the current version of metadata for a blob to be viewed by the user or downloaded by the supervisor.
     *
     * @param key Key for the blob
     * @param who Is the subject having the read privilege for the blob
     * @return AtomicOutputStream returns a stream into which the data can be written
     */
    public abstract ReadableBlobMeta getBlobMeta(String key, Subject who) throws AuthorizationException, KeyNotFoundException;

    /**
     * Sets leader elector (only used by LocalFsBlobStore to help sync blobs between Nimbi.
     */

    public abstract void setLeaderElector(ILeaderElector leaderElector);

    /**
     * Sets the metadata with renewed acls for the blob.
     *
     * @param key  Key for the blob
     * @param meta Metadata which contains the updated acls information
     * @param who  Is the subject having the write privilege for the blob
     */
    public abstract void setBlobMeta(String key, SettableBlobMeta meta, Subject who) throws AuthorizationException, KeyNotFoundException;

    /**
     * Deletes the blob data and metadata.
     *
     * @param key Key for the blob
     * @param who Is the subject having write privilege for the blob
     */
    public abstract void deleteBlob(String key, Subject who) throws AuthorizationException, KeyNotFoundException;

    /**
     * Gets the InputStream to read the blob details.
     *
     * @param key Key for the blob
     * @param who Is the subject having the read privilege for the blob
     * @return InputStreamWithMeta has the additional file length and version information
     */
    public abstract InputStreamWithMeta getBlob(String key, Subject who) throws AuthorizationException, KeyNotFoundException;

    /**
     * Returns an iterator with all the list of keys currently available on the blob store.
     *
     * @return {@code Iterator<String>}
     */
    public abstract Iterator<String> listKeys();

    /**
     * Gets the replication factor of the blob.
     *
     * @param key Key for the blob
     * @param who Is the subject having the read privilege for the blob
     * @return BlobReplication object containing the replication factor for the blob
     */
    public abstract int getBlobReplication(String key, Subject who) throws Exception;

    /**
     * Modifies the replication factor of the blob.
     *
     * @param key         Key for the blob
     * @param replication The replication factor the blob has to be set
     * @param who         Is the subject having the update privilege for the blob
     * @return BlobReplication object containing the updated replication factor for the blob
     */
    public abstract int updateBlobReplication(String key, int replication, Subject who) throws AuthorizationException, KeyNotFoundException,
        IOException;

    @Override
    public void close() {
        shutdown();
    }

    /**
     * Filters keys based on the KeyFilter passed as the argument.
     *
     * @param filter KeyFilter
     * @param <R>    Type
     * @return Set of filtered keys
     */
    public <R> Set<R> filterAndListKeys(KeyFilter<R> filter) {
        Set<R> ret = new HashSet<R>();
        Iterator<String> keys = listKeys();
        while (keys.hasNext()) {
            String key = keys.next();
            R filtered = filter.filter(key);
            if (filtered != null) {
                ret.add(filtered);
            }
        }
        return ret;
    }

    /**
     * Reads the blob from the blob store and writes it into the output stream.
     *
     * @param key Key for the blob
     * @param out Output stream
     * @param who Is the subject having read privilege for the blob
     */
    public void readBlobTo(String key, OutputStream out, Subject who) throws IOException, KeyNotFoundException, AuthorizationException {
        InputStreamWithMeta in = getBlob(key, who);
        if (in == null) {
            throw new IOException("Could not find " + key);
        }
        byte[] buffer = new byte[2048];
        int len = 0;
        try {
            while ((len = in.read(buffer)) > 0) {
                out.write(buffer, 0, len);
            }
        } finally {
            in.close();
            out.flush();
        }
    }

    /**
     * Wrapper around readBlobTo which returns a ByteArray output stream.
     *
     * @param key Key for the blob
     * @param who Is the subject having the read privilege for the blob
     */
    public byte[] readBlob(String key, Subject who) throws IOException, KeyNotFoundException, AuthorizationException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        readBlobTo(key, out, who);
        byte[] bytes = out.toByteArray();
        out.close();
        return bytes;
    }

    /**
     * Get IDs stored in blob store.
     * @return a set of all of the topology ids with special data stored in the blob store.
     */
    public Set<String> storedTopoIds() {
        return filterAndListKeys(TO_TOPO_ID);
    }

    /**
     * Updates the last update time of existing blobs in the blobstore to the current time.
     *
     * @throws IOException on any error
     */
    public void updateLastBlobUpdateTime() throws IOException {
        // default implementation is a NOOP.
    }

    /**
     * Validates that the blob update time of the blobstore is up to date with the current existing blobs.
     *
     * @throws IOException on any error
     */
    public void validateBlobUpdateTime() throws IOException {
        // default implementation is a NOOP.
    }

    /**
     * Blob store implements its own version of iterator to list the blobs.
     */
    public static class KeyTranslationIterator implements Iterator<String> {
        private Iterator<String> it = null;
        private String next = null;
        private String prefix = null;

        public KeyTranslationIterator(Iterator<String> it, String prefix) throws IOException {
            this.it = it;
            this.prefix = prefix;
            primeNext();
        }

        private void primeNext() {
            next = null;
            while (it.hasNext()) {
                String tmp = it.next();
                if (tmp.startsWith(prefix)) {
                    next = tmp.substring(prefix.length());
                    return;
                }
            }
        }

        @Override
        public boolean hasNext() {
            return next != null;
        }

        @Override
        public String next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            String current = next;
            primeNext();
            return current;
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException("Delete Not Supported");
        }
    }

    /**
     * Output stream implementation used for reading the metadata and data information.
     */
    protected class BlobStoreFileOutputStream extends AtomicOutputStream {
        private BlobStoreFile part;
        private OutputStream out;

        public BlobStoreFileOutputStream(BlobStoreFile part) throws IOException {
            this.part = part;
            this.out = part.getOutputStream();
        }

        @Override
        public void close() throws IOException {
            try {
                //close means commit
                out.close();
                part.commit();
            } catch (IOException | RuntimeException e) {
                cancel();
                throw e;
            }
        }

        @Override
        public void cancel() throws IOException {
            try {
                out.close();
            } finally {
                part.cancel();
            }
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
        }

        @Override
        public void write(byte[] b) throws IOException {
            out.write(b);
        }

        @Override
        public void write(byte[] b, int offset, int len) throws IOException {
            out.write(b, offset, len);
        }
    }

    /**
     * Input stream implementation used for writing both the metadata containing the acl information and the blob data.
     */
    protected class BlobStoreFileInputStream extends InputStreamWithMeta {
        private BlobStoreFile part;
        private InputStream in;

        public BlobStoreFileInputStream(BlobStoreFile part) throws IOException {
            this.part = part;
            this.in = part.getInputStream();
        }

        @Override
        public long getVersion() throws IOException {
            return part.getModTime();
        }

        @Override
        public int read() throws IOException {
            return in.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return in.read(b, off, len);
        }

        @Override
        public int read(byte[] b) throws IOException {
            return in.read(b);
        }

        @Override
        public int available() throws IOException {
            return in.available();
        }

        @Override
        public long getFileLength() throws IOException {
            return part.getFileLength();
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
