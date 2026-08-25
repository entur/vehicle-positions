package org.entur.vehicles.service.planned;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Turns an aggregated NeTEx zip into a {@link PlannedDataset}. Each XML entry is streamed
 * independently: a malformed entry is logged and skipped, and whatever it yielded before
 * failing is kept. The load as a whole fails only if the zip is unreadable or contains no
 * line files at all - a shared-data-only zip is a broken export, not a small one.
 */
@Component
public class PlannedDataLoader {

    private static final Logger LOG = LoggerFactory.getLogger(PlannedDataLoader.class);

    private final NetexPlannedDataExtractor extractor = new NetexPlannedDataExtractor();

    public PlannedDataset load(Path zip) throws PlannedDataLoadException {
        PlannedDataset.Builder builder = new PlannedDataset.Builder();
        int lineFiles = 0;
        int failedEntries = 0;

        try (ZipFile zipFile = new ZipFile(zip.toFile())) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".xml")) {
                    continue;
                }
                try (InputStream in = new BufferedInputStream(zipFile.getInputStream(entry), 1 << 16)) {
                    extractor.extract(in, builder);
                    if (isLineFile(entry.getName())) {
                        lineFiles++;
                    }
                } catch (Exception e) {
                    failedEntries++;
                    LOG.error("Skipping NeTEx entry {} - {}", entry.getName(), e.toString());
                }
            }
        } catch (IOException e) {
            throw new PlannedDataLoadException("Could not read NeTEx zip " + zip, e);
        }

        if (lineFiles == 0) {
            throw new PlannedDataLoadException("NeTEx zip " + zip + " contains no parseable line file");
        }
        if (failedEntries > 0) {
            LOG.warn("{} NeTEx entries were skipped due to parse errors", failedEntries);
        }
        return builder.build();
    }

    /**
     * Aggregated exports name shared files {@code _XXX_shared_data.xml} (leading underscore)
     * and everything else {@code XXX_XXX-Line-...xml}.
     */
    static boolean isLineFile(String entryName) {
        String base = entryName.substring(entryName.lastIndexOf('/') + 1);
        return !base.startsWith("_");
    }
}
