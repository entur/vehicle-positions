package org.entur.vehicles.service.snapshot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Fetches an export and tells the caller which version it got. The ETag from the GET that
 * produced the bytes is what snapshot keys are built from, so a HEAD-then-GET race with a
 * replaced export can never mislabel a snapshot. {@code file:} URLs are copied and carry
 * no ETag, which keeps tests and local runs on the same code path.
 * <p>
 * Two timeouts, because {@code HttpRequest.timeout} only bounds the time to the response
 * headers: {@code timeout} (60 s by default) covers connect and headers, and
 * {@code bodyDeadline} (10 minutes by default) bounds the whole transfer, so a stalled
 * body cannot hang startup forever.
 */
public final class ExportDownloader {

    private static final Logger LOG = LoggerFactory.getLogger(ExportDownloader.class);

    private final HttpClient client;
    private final Duration timeout;
    private final Duration bodyDeadline;

    public ExportDownloader() {
        this(Duration.ofSeconds(60));
    }

    public ExportDownloader(Duration timeout) {
        this(timeout, Duration.ofMinutes(10));
    }

    public ExportDownloader(Duration timeout, Duration bodyDeadline) {
        this.timeout = timeout;
        this.bodyDeadline = bodyDeadline;
        this.client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** The export's ETag, or empty when the URL is not HTTP, the request fails, or there is no ETag. Never throws. */
    public Optional<String> head(String url) {
        if (!isHttp(url)) {
            return Optional.empty();
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .timeout(timeout)
                    .build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() / 100 != 2) {
                LOG.info("HEAD {} returned {}", url, response.statusCode());
                return Optional.empty();
            }
            return response.headers().firstValue("ETag");
        } catch (IllegalArgumentException e) {
            LOG.info("HEAD {} failed: {}", url, e.toString());
            return Optional.empty();
        } catch (IOException e) {
            LOG.info("HEAD {} failed: {}", url, e.toString());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /**
     * Downloads the export to {@code target} and returns the ETag of what was downloaded, if the
     * server sent one. Fails with an {@link IOException} if the transfer is not finished within
     * the body deadline.
     */
    public Optional<String> download(String url, Path target) throws IOException {
        if (!isHttp(url)) {
            try {
                Files.copy(Path.of(URI.create(url)), target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IllegalArgumentException e) {
                throw new IOException("Malformed URL " + url, e);
            }
            return Optional.empty();
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().timeout(timeout).build();
            CompletableFuture<HttpResponse<Path>> future =
                    client.sendAsync(request, HttpResponse.BodyHandlers.ofFile(target));
            HttpResponse<Path> response;
            try {
                response = future.get(bodyDeadline.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                throw new IOException("Download of " + url + " exceeded " + bodyDeadline);
            } catch (ExecutionException e) {
                if (e.getCause() instanceof IOException cause) {
                    throw cause;
                }
                throw new IOException("GET " + url + " failed", e.getCause());
            }
            if (response.statusCode() / 100 != 2) {
                throw new IOException("GET " + url + " returned " + response.statusCode());
            }
            return response.headers().firstValue("ETag");
        } catch (IllegalArgumentException e) {
            throw new IOException("Malformed URL " + url, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while downloading " + url, e);
        }
    }

    private static boolean isHttp(String url) {
        return url.startsWith("http://") || url.startsWith("https://");
    }
}
