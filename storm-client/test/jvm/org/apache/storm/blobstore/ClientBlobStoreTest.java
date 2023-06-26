package org.apache.storm.blobstore;

import static org.junit.Assert.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.LinkedList;
import java.util.List;

import org.apache.storm.*;
import org.apache.storm.generated.*;
import org.apache.storm.utils.Utils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ClientBlobStoreTest {

    private String blobACL;
    private Integer replicationFactor;
    private String keyBlob;
    private String blobWrites;
    private ClientBlobStore clientBlobStore;
    private String accessControlAdminOnly;
    private String accessControlWriteOnly;
    private Integer numKeys;
    //	ACL is in the form [uo]:[username]:[r-][w-][a-] can be comma separated list (requires admin access).

    @Before
    public void setUp() throws Exception {
        Config theconf = new Config();
        theconf.putAll(Utils.readStormConfig());
        this.clientBlobStore = Utils.getClientBlobStore(theconf);
    }

    @After
    public void tearDown() throws Exception {
    }


    @Test(expected = IOException.class)
    public void writeFailure() throws IOException, AuthorizationException, KeyNotFoundException, KeyAlreadyExistsException {
        //update ACL of a blob
        String blobKey2 = "some_key";
        AccessControl updateAcl = BlobStoreAclHandler.parseAccessControl(this.accessControlAdminOnly); //u:USER:--a
        List<AccessControl> updateAcls = new LinkedList<AccessControl>();
        updateAcls.add(updateAcl);
        SettableBlobMeta modifiedSettableBlobMeta = new SettableBlobMeta(updateAcls);
        clientBlobStore.setBlobMeta(blobKey2, modifiedSettableBlobMeta);
        AtomicOutputStream blobStream = clientBlobStore.createBlob(this.keyBlob, modifiedSettableBlobMeta);
        blobStream.write(this.blobWrites.getBytes()); //test
        //vedere se cambiando access control, sul blob è possibile leggere o scrivere
    }

    @Test
    public void test() throws IOException, AuthorizationException, KeyAlreadyExistsException, KeyNotFoundException {


        //creating ACLs
//		String stringBlobACL = "u:username:rwa";
        AccessControl blobACL = BlobStoreAclHandler.parseAccessControl(this.blobACL);
        List<AccessControl> acls = new LinkedList<AccessControl>();
        acls.add(blobACL); // more ACLs can be added here
        SettableBlobMeta settableBlobMeta = new SettableBlobMeta(acls);
        //test This
        settableBlobMeta.set_replication_factor(this.replicationFactor); // Here we can set the replication factor


        //creating blob
        AtomicOutputStream blobStream = clientBlobStore.createBlob(this.keyBlob, settableBlobMeta); //fare numKey volte per aggiungerne diversi
        blobStream.write(this.blobWrites.getBytes()); //test
        blobStream.close();

        List<AccessControl> updateAcls = new LinkedList<AccessControl>();
        AccessControl updateAcl = BlobStoreAclHandler.parseAccessControl(this.accessControlAdminOnly); //u:USER:--a
        SettableBlobMeta modifiedSettableBlobMeta = new SettableBlobMeta(updateAcls);


        //Now set write only
        String blobKey2 = "some_key";
        updateAcl = BlobStoreAclHandler.parseAccessControl(this.accessControlWriteOnly); //"u:USER:-w-"
        updateAcls = new LinkedList<AccessControl>();
        updateAcls.add(updateAcl);
        modifiedSettableBlobMeta = new SettableBlobMeta(updateAcls);
        clientBlobStore.setBlobMeta(blobKey2, modifiedSettableBlobMeta);

        //read a blob
        InputStreamWithMeta blobInputStream = clientBlobStore.getBlob(this.keyBlob);
        BufferedReader r = new BufferedReader(new InputStreamReader(blobInputStream));
        String blobContents =  r.readLine();

        assertEquals(this.blobWrites,blobContents);

        //delete a blob
        String blobKey5 = "some_key";
        clientBlobStore.deleteBlob(blobKey5); //test null

        assertNull(blobKey5);

    }

}