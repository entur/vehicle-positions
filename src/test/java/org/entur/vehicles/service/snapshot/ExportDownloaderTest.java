package org.entur.vehicles.service.snapshot;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

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

    /**
     * {@code HttpRequest.timeout} only bounds the time to the response headers, so a server that
     * sends headers and then stalls mid-body would hang the load forever without the deadline.
     */
    @Test
    public void aStalledBodyHitsTheDeadline(@TempDir Path dir) throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/stalled", exchange -> {
            exchange.getResponseHeaders().add("ETag", "\"etag-1\"");
            exchange.sendResponseHeaders(200, 1000);
            OutputStream out = exchange.getResponseBody();
            out.write(new byte[10]);
            out.flush();
            try {
                // Stall without closing. Released only by the test's cleanup, so no thread lingers.
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/stalled";
            ExportDownloader downloader = new ExportDownloader(Duration.ofSeconds(5), Duration.ofMillis(500));
            Path target = dir.resolve("stalled.zip");

            assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                    assertThatThrownBy(() -> downloader.download(url, target))
                            .isInstanceOf(IOException.class)
                            .hasMessageContaining("exceeded"));
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    @Test
    public void malformedUrlsAreHandled(@TempDir Path dir) {
        ExportDownloader downloader = new ExportDownloader(Duration.ofSeconds(5));

        assertThat(downloader.head("http://[bad")).isEmpty();
        assertThatThrownBy(() -> downloader.download("http://[bad", dir.resolve("x")))
                .isInstanceOf(IOException.class);
    }
}
