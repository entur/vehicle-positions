package org.entur.vehicles.service.snapshot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class ExportDownloaderTest {

    @Test
    public void headReturnsTheEtag(@TempDir Path dir) throws Exception {
        Path export = Files.writeString(dir.resolve("export.zip"), "payload");
        try (EtagHttpServer server = new EtagHttpServer(export, "\"etag-1\"")) {
            ExportDownloader downloader = new ExportDownloader(Duration.ofSeconds(5));

            assertThat(downloader.head(server.url())).contains("\"etag-1\"");
            assertThat(server.headCount()).isEqualTo(1);
            assertThat(server.getCount()).isZero();
        }
    }

    @Test
    public void headIsEmptyWithoutAnEtagOrOnFailure(@TempDir Path dir) throws Exception {
        Path export = Files.writeString(dir.resolve("export.zip"), "payload");
        try (EtagHttpServer server = new EtagHttpServer(export, null)) {
            ExportDownloader downloader = new ExportDownloader(Duration.ofSeconds(5));

            assertThat(downloader.head(server.url())).isEmpty();
            assertThat(downloader.head("http://127.0.0.1:1/nothing")).isEmpty();
            assertThat(downloader.head(export.toUri().toString())).isEmpty();
        }
    }

    @Test
    public void downloadWritesTheBodyAndReturnsTheEtag(@TempDir Path dir) throws Exception {
        Path export = Files.writeString(dir.resolve("export.zip"), "payload");
        try (EtagHttpServer server = new EtagHttpServer(export, "\"etag-1\"")) {
            ExportDownloader downloader = new ExportDownloader(Duration.ofSeconds(5));
            Path target = dir.resolve("downloaded.zip");

            assertThat(downloader.download(server.url(), target)).contains("\"etag-1\"");
            assertThat(Files.readString(target)).isEqualTo("payload");
        }
    }

    @Test
    public void downloadOfAFileUrlCopiesWithoutAnEtag(@TempDir Path dir) throws Exception {
        Path export = Files.writeString(dir.resolve("export.zip"), "payload");
        ExportDownloader downloader = new ExportDownloader(Duration.ofSeconds(5));
        Path target = dir.resolve("downloaded.zip");

        assertThat(downloader.download(export.toUri().toString(), target)).isEmpty();
        assertThat(Files.readString(target)).isEqualTo("payload");
    }

    @Test
    public void downloadFailuresThrow(@TempDir Path dir) {
        ExportDownloader downloader = new ExportDownloader(Duration.ofSeconds(5));

        assertThatThrownBy(() -> downloader.download("http://127.0.0.1:1/nothing", dir.resolve("x")))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> downloader.download(dir.resolve("missing.zip").toUri().toString(), dir.resolve("y")))
                .isInstanceOf(IOException.class);
    }

    @Test
    public void malformedUrlsAreHandled(@TempDir Path dir) {
        ExportDownloader downloader = new ExportDownloader(Duration.ofSeconds(5));

        assertThat(downloader.head("http://[bad")).isEmpty();
        assertThatThrownBy(() -> downloader.download("http://[bad", dir.resolve("x")))
                .isInstanceOf(IOException.class);
    }
}
