package org.entur.vehicles.service.snapshot;

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import com.google.cloud.storage.StorageOptions;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Snapshot objects in a GCS bucket. Credentials come from Application Default Credentials,
 * the same route the Pub/Sub subscribers use, so the pod's Workload Identity binding covers
 * both. Not unit tested: it is one client call per method, and the dev rollout exercises it.
 */
public final class GcsSnapshotStore implements SnapshotStore {

    private static final String CONTENT_TYPE = "application/octet-stream";

    private final Storage storage;
    private final String bucket;

    public GcsSnapshotStore(String bucket) {
        this(StorageOptions.getDefaultInstance().getService(), bucket);
    }

    GcsSnapshotStore(Storage storage, String bucket) {
        this.storage = storage;
        this.bucket = bucket;
    }

    @Override
    public Optional<InputStream> open(String objectName) throws IOException {
        try {
            Blob blob = storage.get(BlobId.of(bucket, objectName));
            if (blob == null) {
                return Optional.empty();
            }
            return Optional.of(Channels.newInputStream(blob.reader()));
        } catch (StorageException e) {
            throw new IOException("Could not open gs://" + bucket + "/" + objectName, e);
        }
    }

    @Override
    public boolean putIfAbsent(String objectName, Path file) throws IOException {
        try {
            storage.createFrom(info(objectName), file, Storage.BlobWriteOption.doesNotExist());
            return true;
        } catch (StorageException e) {
            if (e.getCode() == 412) {
                return false;
            }
            throw new IOException("Could not upload gs://" + bucket + "/" + objectName, e);
        }
    }

    @Override
    public void put(String objectName, Path file) throws IOException {
        try {
            storage.createFrom(info(objectName), file);
        } catch (StorageException e) {
            throw new IOException("Could not upload gs://" + bucket + "/" + objectName, e);
        }
    }

    private BlobInfo info(String objectName) {
        return BlobInfo.newBuilder(BlobId.of(bucket, objectName)).setContentType(CONTENT_TYPE).build();
    }
}
