package org.syncany.tests.plugin.azure;

import com.microsoft.azure.storage.CloudStorageAccount;
import com.microsoft.azure.storage.blob.CloudBlobClient;
import com.microsoft.azure.storage.blob.CloudBlobContainer;
import com.microsoft.azure.storage.blob.CloudBlockBlob;
import org.junit.Before;
import org.junit.Test;
import org.syncany.plugins.Plugin;
import org.syncany.plugins.Plugins;
import org.syncany.plugins.azure.AzureTransferManager;
import org.syncany.plugins.azure.AzureTransferPlugin;
import org.syncany.plugins.azure.AzureTransferSettings;
import org.syncany.plugins.transfer.StorageException;
import org.syncany.plugins.transfer.TransferManager;
import org.syncany.plugins.transfer.TransferPlugin;

import java.io.*;
import java.util.Map;
import java.util.Random;

import static org.junit.Assert.*;

public class AzurePluginTest {
    private static final int TEST_FILE_SIZE = 1024 * 1024;
    private static final String PLUGIN_NAME = "azure";
    private static final String DEVELOPMENT_STORAGE_CONNECTION_STRING = "UseDevelopmentStorage=true;";
    private static final String CONTAINER_NAME = "syncanytest";

    private AzureTransferSettings validTransferSettings;
    private CloudBlobContainer container;

    @Before
    public void setUp() throws Exception {
        container = setupDevelopmentContainer();
        container.deleteIfExists();

        TransferPlugin pluginInfo = Plugins.get(PLUGIN_NAME, TransferPlugin.class);
        validTransferSettings = pluginInfo.createEmptySettings();
        setupDevelopmentStorageCredentials();
    }

    private CloudBlobContainer setupDevelopmentContainer() throws Exception {
        CloudStorageAccount cloudStorageAccount = CloudStorageAccount.parse(DEVELOPMENT_STORAGE_CONNECTION_STRING);
        CloudBlobClient cloudBlobClient = cloudStorageAccount.createCloudBlobClient();
        return cloudBlobClient.getContainerReference(CONTAINER_NAME);
    }

    private void setupDevelopmentStorageCredentials() {
        validTransferSettings.accountKey = "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==;" + DEVELOPMENT_STORAGE_CONNECTION_STRING;
        validTransferSettings.accountName = "devstoreaccount1";

        validTransferSettings.containerName = CONTAINER_NAME;
        validTransferSettings.httpsUsed = false;
    }

    @Test
    public void testLoadPluginAndCreateTransferManager() throws Exception {
        loadPluginAndCreateTransferManager();
    }

    @Test
    public void testLocalPluginInfo() {
        Plugin pluginInfo = Plugins.get(PLUGIN_NAME);

        assertNotNull("PluginInfo should not be null.", pluginInfo);
        assertEquals("Plugin ID should be 'azure'.", PLUGIN_NAME, pluginInfo.getId());
        assertNotNull("Plugin version should not be null.", pluginInfo.getVersion());
        assertNotNull("Plugin name should not be null.", pluginInfo.getName());
    }

    @Test(expected=StorageException.class)
    public void testCannotCreatePluginWithIncorrectSettings() throws Exception {
        TransferPlugin pluginInfo = Plugins.get(PLUGIN_NAME, TransferPlugin.class);

        AzureTransferSettings settings = pluginInfo.createEmptySettings();
        TransferManager transferManager = pluginInfo.createTransferManager(settings, null);
    }

    @Test(expected=StorageException.class)
    public void testCannotCreatePluginWithIncorrectCredentials() throws Exception {
        TransferPlugin pluginInfo = Plugins.get(PLUGIN_NAME, TransferPlugin.class);

        AzureTransferSettings settings = pluginInfo.createEmptySettings();
        validTransferSettings.accountKey = "wrong-key";
        validTransferSettings.accountName = "wrong-name";
        validTransferSettings.containerName = "syncany-test";

        TransferManager transferManager = pluginInfo.createTransferManager(settings, null);
    }

    @Test
    public void canCreateContainer() throws Exception {
        TransferManager transferManager = loadPluginAndCreateTransferManager();
        transferManager.init(true);
        assertTrue(container.exists());
    }

    @Test
    public void canUploadFile() throws Exception {
        TransferManager transferManager = loadPluginAndCreateTransferManager();
        transferManager.init(true);

        File localfile = new File("localfile");
        localfile.delete();
        localfile.createNewFile();
        FileOutputStream localfileStream = new FileOutputStream("localfile");
        byte[] bytes = getRandomBytes();
        localfileStream.write(bytes);
        localfileStream.close();

        transferManager.upload(localfile, new StubRemoteFile("remotefile"));

        CloudBlockBlob remotefile = container.getBlockBlobReference("remotefile");
        byte[] remoteBytes = new byte[TEST_FILE_SIZE];
        int readLength = remotefile.downloadToByteArray(remoteBytes, 0);

        assertEquals(readLength, TEST_FILE_SIZE);
        assertArrayEquals(bytes, remoteBytes);
    }

    @Test
    public void canDownloadFile() throws Exception{
        TransferManager transferManager = loadPluginAndCreateTransferManager();
        transferManager.init(true);

        byte[] remoteBytes = getRandomBytes();
        CloudBlockBlob remoteblob = container.getBlockBlobReference("remotefile");
        uploadBytesToBlob(remoteBytes, remoteblob);

        StubRemoteFile remoteFile = new StubRemoteFile("remotefile");
        File localfile = new File("localfile");
        localfile.delete();

        byte[] localBytes = new byte[TEST_FILE_SIZE];
        transferManager.download(remoteFile, localfile);
        int readLength = new FileInputStream(localfile).read(localBytes, 0, TEST_FILE_SIZE);

        assertEquals(readLength, TEST_FILE_SIZE);
        assertArrayEquals(remoteBytes, localBytes);
    }

    @Test
    public void canDeleteFile() throws Exception {
        TransferManager transferManager = loadPluginAndCreateTransferManager();
        transferManager.init(true);

        byte[] remoteBytes = getRandomBytes();
        CloudBlockBlob remoteblob = container.getBlockBlobReference("remotefile");
        uploadBytesToBlob(remoteBytes, remoteblob);

        StubRemoteFile remoteFile = new StubRemoteFile("remotefile");
        transferManager.delete(remoteFile);

        assertFalse(remoteblob.exists());
    }

    @Test
    public void canMoveFile() throws Exception {
        TransferManager transferManager = loadPluginAndCreateTransferManager();
        transferManager.init(true);

        byte[] remoteBytes = getRandomBytes();
        CloudBlockBlob remoteblob = container.getBlockBlobReference("remotefile");
        CloudBlockBlob movedRemoteblob = container.getBlockBlobReference("remotefile2");
        uploadBytesToBlob(remoteBytes, remoteblob);

        StubRemoteFile remoteFile = new StubRemoteFile("remotefile");
        StubRemoteFile movedRemoteFile = new StubRemoteFile("remotefile2");

        transferManager.move(remoteFile, movedRemoteFile);

        assertFalse(remoteblob.exists());
        assertTrue(movedRemoteblob.exists());
    }

    @Test
    public void canListFiles() throws Exception {
        TransferManager transferManager = loadPluginAndCreateTransferManager();
        transferManager.init(true);

        byte[] remoteBytes = getRandomBytes();

        CloudBlockBlob remoteblob = container.getBlockBlobReference("remotefile");
        CloudBlockBlob remoteblob2 = container.getBlockBlobReference("remotefile.txt");

        uploadBytesToBlob(remoteBytes, remoteblob);
        uploadBytesToBlob(remoteBytes, remoteblob2);

        Object[] list = transferManager.list(StubRemoteFile.class).entrySet().toArray();

        assertEquals(((Map.Entry<String, StubRemoteFile>)list[1]).getKey(), "remotefile");
        assertEquals(((Map.Entry<String, StubRemoteFile>)list[1]).getValue().getName(), "remotefile");
        assertEquals(((Map.Entry<String, StubRemoteFile>)list[0]).getKey(), "remotefile.txt");
        assertEquals(((Map.Entry<String, StubRemoteFile>)list[0]).getValue().getName(), "remotefile.txt");
    }

    private byte[] getRandomBytes() {
        byte[] remoteBytes = new byte[TEST_FILE_SIZE];
        new Random().nextBytes(remoteBytes);
        return remoteBytes;
    }

    private void uploadBytesToBlob(byte[] remoteBytes, CloudBlockBlob remoteblob) throws com.microsoft.azure.storage.StorageException, IOException {
        remoteblob.upload(new ByteArrayInputStream(remoteBytes), TEST_FILE_SIZE);
    }

    private TransferManager loadPluginAndCreateTransferManager() throws Exception {
        TransferPlugin pluginInfo = Plugins.get(PLUGIN_NAME, TransferPlugin.class);

        TransferManager transferManager = pluginInfo.createTransferManager(validTransferSettings, null);

        assertEquals("AzureblobstoragePlugin expected.", AzureTransferPlugin.class, pluginInfo.getClass());
        assertEquals("AzureblobstorageConnection expected.", AzureTransferSettings.class, validTransferSettings.getClass());
        assertEquals("AzureblobstorageTransferManager expected.", AzureTransferManager.class, transferManager.getClass());

        return transferManager;
    }
}
