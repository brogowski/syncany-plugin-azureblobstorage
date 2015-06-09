package org.syncany.plugins.azureblobstorage;

import com.microsoft.azure.storage.CloudStorageAccount;
import com.microsoft.azure.storage.blob.*;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.syncany.config.Config;
import org.syncany.plugins.transfer.AbstractTransferManager;
import org.syncany.plugins.transfer.StorageException;
import org.syncany.plugins.transfer.features.ReadAfterWriteConsistent;
import org.syncany.plugins.transfer.features.ReadAfterWriteConsistentFeatureExtension;
import org.syncany.plugins.transfer.files.*;
import org.syncany.plugins.azureblobstorage.AzureblobstorageTransferManager.AzureBlobStorageReadAfterWriteConsistentFeatureExtension;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URISyntaxException;
import java.security.InvalidKeyException;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

@ReadAfterWriteConsistent(extension = AzureBlobStorageReadAfterWriteConsistentFeatureExtension.class)
public class AzureblobstorageTransferManager extends AbstractTransferManager {
    private static final Logger logger = Logger.getLogger(AzureblobstorageTransferManager.class.getSimpleName());
    private static final String MULTICHUNKS_PATH = "/multichunks";
    private static final String DATABASES_PATH = "/databases";
    private static final String ACTIONS_PATH = "/actions";
    private static final String TRANSACTIONS_PATH = "/transactions";
    private static final String TEMPORARY_PATH = "/temporary";

    private CloudBlobClient cloudBlobClient;
    private CloudBlobContainer container;


    public AzureblobstorageTransferManager(AzureblobstorageTransferSettings settings, Config config) throws StorageException {
        super(settings, config);

        String connectionString = getConnectionString(settings);

        trySetupContainer(settings, connectionString);
    }

    private void trySetupContainer(AzureblobstorageTransferSettings settings, String connectionString) throws StorageException {
        try {
            CloudStorageAccount cloudStorageAccount = CloudStorageAccount.parse(connectionString);
            cloudBlobClient = cloudStorageAccount.createCloudBlobClient();
            container = cloudBlobClient.getContainerReference(settings.getContainerName());
        } catch (URISyntaxException e) {
            logger.log(Level.SEVERE, "Could not parse azureblobstorage storage connection string. (Invalid Uri)", e);
            throw new StorageException("Wrong connection string.");
        } catch (InvalidKeyException e) {
            logger.log(Level.SEVERE, "Could not parse azureblobstorage storage connection string. (Invalid Key)", e);
            throw new StorageException("Wrong connection string.");
        } catch (com.microsoft.azure.storage.StorageException e) {
            logger.log(Level.SEVERE, "Could not get container reference for: " + settings.getContainerName(), e);
            throw new StorageException("Wrong container name.");
        }
    }

    private String getConnectionString(AzureblobstorageTransferSettings settings) {
        return "DefaultEndpointsProtocol=" + getProtocol(settings) + ";"
                + "AccountName=" + settings.getAccountName() + ";"
                + "AccountKey=" + settings.getAccountKey();
    }

    private String getProtocol(AzureblobstorageTransferSettings settings) {
        return settings.isHttpsUsed() ? "https" : "http";
    }

    @Override
    public void connect() {

    }

    @Override
    public void disconnect() {

    }

    @Override
    public void init(boolean createIfRequired) throws StorageException {
        connect();

        try {
            if (!testTargetExists() && createIfRequired) {
                this.container.create();
            }
        } catch (Exception e) {
            throw new StorageException("init: Cannot create required directories", e);
        } finally {
            disconnect();
        }
    }

    @Override
    public void download(RemoteFile remoteFile, File localFile) throws StorageException {
        tryDownloadBlobToFile(remoteFile, localFile);
    }

    private void tryDownloadBlobToFile(RemoteFile remoteFile, File localFile) throws StorageException {
        if (!remoteFile.getName().equals(".") && !remoteFile.getName().equals("..")) {
            try {
                File tempFile = createTempFile(localFile.getName());
                downloadBlobToFile(tempFile, getRemoteFileFullPath(remoteFile));
                moveToDestinationFile(tempFile, localFile);
                tempFile.delete();
            } catch (IOException e) {
                logger.log(Level.SEVERE, "Error while downloading file " + remoteFile.getName(), e);
                throw new StorageException(e);
            }
        }
    }

    private void moveToDestinationFile(File srcFile, File destFile) throws IOException {
        if (logger.isLoggable(Level.INFO)) {
            logger.log(Level.INFO, "Azure: Renaming temp file {0} to file {1}", new Object[]{srcFile, destFile});
        }
        destFile.delete();
        FileUtils.moveFile(srcFile, destFile);
    }

    private void downloadBlobToFile(File tempFile, String remotePath) throws IOException, StorageException {
        if (logger.isLoggable(Level.INFO)) {
            logger.log(Level.INFO, "Azure: Downloading {0} to temp file {1}", new Object[]{remotePath, tempFile});
        }
        CloudBlockBlob blob = getCloudBlockBlob(remotePath);
        try {
            blob.downloadToFile(tempFile.getAbsolutePath());
        } catch (com.microsoft.azure.storage.StorageException e) {
            throw new StorageException(e);
        }
    }

    @Override
    public void upload(File localFile, RemoteFile remoteFile) throws StorageException {
        String remotePath = getRemoteFileFullPath(remoteFile);
        String tempRemotePath = "temp-" + remoteFile.getName();

        CloudBlockBlob targetBlob = getCloudBlockBlob(remotePath);
        CloudBlockBlob tempBlob = getCloudBlockBlob(tempRemotePath);

        if (logger.isLoggable(Level.INFO)) {
            logger.log(Level.INFO, "Azure: Uploading {0} to temp file {1}", new Object[]{localFile, tempRemotePath});
        }

        tryUploadToBlob(localFile, tempBlob);

        if (logger.isLoggable(Level.INFO)) {
            logger.log(Level.INFO, "Azure: copying temp file {0} to file {1}", new Object[]{tempRemotePath, remotePath});
        }

        tryMoveBlob(tempBlob, targetBlob);

        tryDeleteBlob(tempBlob);
    }

    private void tryDeleteBlob(CloudBlockBlob blob) throws StorageException {
        try {
            blob.delete();
        } catch (com.microsoft.azure.storage.StorageException e) {
            throw new StorageException(e);
        }
    }

    private void tryMoveBlob(CloudBlockBlob sourceBlob, CloudBlockBlob targetBlob) throws StorageException {
        try {
            targetBlob.startCopyFromBlob(sourceBlob);
            waitForCopy(targetBlob);
        } catch (URISyntaxException e) {
            throw new StorageException(e);
        } catch (com.microsoft.azure.storage.StorageException e) {
            throw new StorageException(e);
        }
    }

    private void waitForCopy(CloudBlockBlob blob) throws StorageException {
        CopyState copyState;
        do {
            copyState = blob.getCopyState();
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                throw new StorageException("Copying interrupted.");
            }
        } while (copyState.getStatus() == CopyStatus.UNSPECIFIED || copyState.getStatus() == CopyStatus.PENDING);
        if (copyState.getStatus() != CopyStatus.SUCCESS) {
            throw new StorageException("Copying failed.");
        }
    }

    private void tryUploadToBlob(File localFile, CloudBlockBlob blob) throws StorageException {
        try {
            blob.upload(new FileInputStream(localFile), localFile.length());
        } catch (com.microsoft.azure.storage.StorageException e) {
            throw new StorageException(e);
        } catch (FileNotFoundException e) {
            throw new StorageException(e);
        } catch (IOException e) {
            throw new StorageException(e);
        }
    }

    @Override
    public boolean delete(RemoteFile remoteFile) throws StorageException {
        String remotePath = getRemoteFileFullPath(remoteFile);

        try {
            tryDeleteBlob(getCloudBlockBlob(remotePath));
        } catch (StorageException e) {
            return false;
        }
        return true;
    }

    @Override
    public void move(RemoteFile sourceFile, RemoteFile targetFile) throws StorageException {
        String sourceRemotePath = getRemoteFileFullPath(sourceFile);
        String targetRemotePath = getRemoteFileFullPath(targetFile);

        CloudBlockBlob sourceBlob = getCloudBlockBlob(sourceRemotePath);
        CloudBlockBlob targetBlob = getCloudBlockBlob(targetRemotePath);

        tryMoveBlob(sourceBlob, targetBlob);
        delete(sourceFile);
    }

    private CloudBlockBlob getCloudBlockBlob(String sourceRemotePath) throws StorageException {
        CloudBlockBlob sourceBlob;
        try {
            sourceBlob = this.container.getBlockBlobReference(sourceRemotePath);
        } catch (URISyntaxException e) {
            logger.log(Level.SEVERE, "Error while getting blob reference " + sourceRemotePath, e);
            throw new StorageException(e);
        } catch (com.microsoft.azure.storage.StorageException e) {
            logger.log(Level.SEVERE, "Error while getting blob reference " + sourceRemotePath, e);
            throw new StorageException(e);
        }
        return sourceBlob;
    }

    @Override
    public <T extends RemoteFile> Map<String, T> list(Class<T> remoteFileClass) throws StorageException {
        String remoteFilePath = getRemoteFilePath(remoteFileClass);

        Iterable<ListBlobItem> blobs = this.container.listBlobs(remoteFilePath);

        Map<String, T> remoteFiles = new HashMap<>();

        for (ListBlobItem blob : blobs) {
            String path = blob.getUri().getPath();
            String name = path.substring(path.lastIndexOf('/') + 1);
            T remoteFile = RemoteFile.createRemoteFile(name, remoteFileClass);
            remoteFiles.put(name, remoteFile);
        }

        return remoteFiles;
    }

    @Override
    public String getRemoteFilePath(Class<? extends RemoteFile> remoteFile) {
        if (remoteFile.equals(MultichunkRemoteFile.class)) {
            return MULTICHUNKS_PATH;
        } else if (remoteFile.equals(DatabaseRemoteFile.class) || remoteFile.equals(CleanupRemoteFile.class)) {
            return DATABASES_PATH;
        } else if (remoteFile.equals(ActionRemoteFile.class)) {
            return ACTIONS_PATH;
        } else if (remoteFile.equals(TransactionRemoteFile.class)) {
            return TRANSACTIONS_PATH;
        } else if (remoteFile.equals(TempRemoteFile.class)) {
            return TEMPORARY_PATH;
        } else {
            return "";
        }
    }

    @Override
    public boolean testTargetCanWrite() {
        try {
            if (testTargetExists()) {
                File tempFile = File.createTempFile("syncany-write-test", "tmp");

                CloudBlockBlob blob = getCloudBlockBlob("/syncany-write-test");
                tryUploadToBlob(tempFile, blob);
                tryDeleteBlob(blob);

                tempFile.delete();

                logger.log(Level.INFO, "testTargetCanWrite: Can write, test file created/deleted successfully.");
                return true;
            } else {
                logger.log(Level.INFO, "testTargetCanWrite: Can NOT write, target does not exist.");
                return false;
            }
        } catch (IOException e) {
            logger.log(Level.INFO, "testTargetCanWrite: Can NOT write to target.", e);
            return false;
        } catch (StorageException e) {
            logger.log(Level.INFO, "testTargetCanWrite: Can NOT write to target.", e);
            return false;
        }
    }

    @Override
    public boolean testTargetExists() {
        try {
            if (this.container.exists()) {
                logger.log(Level.INFO, "testTargetExists: Target does exist.");
                return true;
            } else {
                logger.log(Level.INFO, "testTargetExists: Target does NOT exist.");
                return false;
            }
        } catch (com.microsoft.azure.storage.StorageException e) {
            return false;
        }
    }

    @Override
    public boolean testTargetCanCreate() {
        return true;
    }

    @Override
    public boolean testRepoFileExists() {
        try {
            String repoFilePath = getRemoteFileFullPath(new SyncanyRemoteFile());

            if (getCloudBlockBlob(repoFilePath).exists()) {
                logger.log(Level.INFO, "testRepoFileExists: Repo file exists at " + repoFilePath);
                return true;
            } else {
                logger.log(Level.INFO, "testRepoFileExists: Repo file DOES NOT exist at " + repoFilePath);
                return false;
            }
        } catch (Exception e) {
            logger.log(Level.INFO, "testRepoFileExists: Exception when trying to check repo file existence.", e);
            return false;
        }
    }

    private String getRemoteFileFullPath(RemoteFile remoteFile) {
        String path = getRemoteFilePath(remoteFile.getClass()) + "/" + remoteFile.getName();

        if(path.startsWith("/"))
            return path.substring(1);

        return path;
    }

    public static class AzureBlobStorageReadAfterWriteConsistentFeatureExtension implements ReadAfterWriteConsistentFeatureExtension {
        private final AzureblobstorageTransferManager transferManager;

        public AzureBlobStorageReadAfterWriteConsistentFeatureExtension(AzureblobstorageTransferManager transferManager) {
            this.transferManager = transferManager;
        }

        @Override
        public boolean exists(RemoteFile remoteFile) throws StorageException {
            try {
                return transferManager.getCloudBlockBlob(transferManager.getRemoteFileFullPath(remoteFile)).exists();
            } catch (com.microsoft.azure.storage.StorageException e) {
                throw new StorageException(e);
            }
        }
    }
}
