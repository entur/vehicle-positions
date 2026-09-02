package org.entur.vehicles.service.snapshot;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Serves one file at {@code /export.zip} with an ETag header, answering HEAD and GET, so
 * tests can drive the snapshot path without touching the network. The {@code file:} scheme
 * carries no headers, which is why this exists.
 */
public final class EtagHttpServer implements AutoCloseable {

    private final HttpServer server;
    private volatile Path file;
    private volatile String etag;
    private final AtomicInteger headCount = new AtomicInteger();
    private final AtomicInteger getCount = new AtomicInteger();

    public EtagHttpServer(Path file, String etag) throws IOException {
        this.file = file;
        this.etag = etag;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/export.zip", exchange -> {
            byte[] body = Files.readAllBytes(this.file);
            if (this.etag != null) {
                exchange.getResponseHeaders().add("ETag", this.etag);
            }
            if ("HEAD".equals(exchange.getRequestMethod())) {
                headCount.incrementAndGet();
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
                return;
            }
            getCount.incrementAndGet();
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/export.zip";
    }

    public void setEtag(String etag) {
        this.etag = etag;
    }

    public void setFile(Path file) {
        this.file = file;
    }

    public int headCount() {
        return headCount.get();
    }

    public int getCount() {
        return getCount.get();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
