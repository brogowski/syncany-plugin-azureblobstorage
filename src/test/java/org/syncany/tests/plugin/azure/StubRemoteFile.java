package org.syncany.tests.plugin.azure;

import org.syncany.plugins.transfer.StorageException;
import org.syncany.plugins.transfer.files.RemoteFile;

public class StubRemoteFile extends RemoteFile {
    public StubRemoteFile(String name) throws StorageException {
        super(name);
    }
}
