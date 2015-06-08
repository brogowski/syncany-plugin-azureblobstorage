package org.syncany.plugins.azure;

import org.simpleframework.xml.Element;
import org.simpleframework.xml.core.Validate;
import org.syncany.plugins.transfer.Encrypted;
import org.syncany.plugins.transfer.Setup;
import org.syncany.plugins.transfer.StorageException;
import org.syncany.plugins.transfer.TransferSettings;


public class AzureBlobStorageTransferSettings extends TransferSettings {

    @Element(name = "accountName", required = true)
    @Setup(order = 1, description = "Account name")
    public String accountName;

    @Element(name = "accountKey", required = true)
    @Setup(order = 2, description = "Account key")
    @Encrypted
    public String accountKey;

	@Element(name = "containerName", required = true)
	@Setup(order = 3, description = "Container name")
	public String containerName;

	@Element(name = "httpsUsed", required = false)
	@Setup(order = 4, visible = true, description = "Should https protocol be used?")
	public boolean httpsUsed = true;

    public boolean isHttpsUsed() {
        return httpsUsed;
    }

    public String getContainerName() {
        return containerName;
    }

    public String getAccountName() {
        return accountName;
    }

    public String getAccountKey() {
        return accountKey;
    }

    @Validate
    public void ValidateSettings() throws StorageException {
        if(!containerName.equals(containerName.toLowerCase()))
            throw new StorageException("Container name must be lower case only.");
    }
}
