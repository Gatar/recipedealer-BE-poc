package com.gatar.recipedealer.webimage;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.gatar.recipedealer.blob.BlobRef;
import com.gatar.recipedealer.blob.BlobStore;

/**
 * Downloads one image into the blob store. Follows redirects itself, so that every hop is checked against private
 * addresses (unless allowed for tests).
 */
@Component
public class WebImageDownloader {

    static final int MAX_REDIRECTS = 5;
    private static final String USER_AGENT = "Mozilla/5.0 (compatible; RecipeDealer/1.0)";
    private static final Set<Integer> REDIRECTS = Set.of(301, 302, 303, 307, 308);
    /** Characters that are not allowed in a URI and that pages still put into image URLs. */
    private static final String UNSAFE = " \"<>\\^`{|}";

    public record Image(String mediaType, BlobRef blob) {
    }

    /** A download that will not work on a retry: missing, not an image, too large, a forbidden address, … */
    public static class PermanentFailure extends IOException {
        PermanentFailure(String message) {
            super(message);
        }
    }

    private final BlobStore blobs;
    private final WebImageProperties properties;
    private final HttpClient http;

    public WebImageDownloader(BlobStore blobs, WebImageProperties properties) {
        this.blobs = blobs;
        this.properties = properties;
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(properties.timeout())
                .build();
    }

    /** Stores the image as a blob (the caller records the blob row). */
    public Image download(String url) throws IOException, InterruptedException {
        URI uri = toUri(url);
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            checkAddress(uri);
            var request = HttpRequest.newBuilder(uri)
                    .timeout(properties.timeout())
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "image/*")
                    .GET()
                    .build();
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                int status = response.statusCode();
                if (REDIRECTS.contains(status)) {
                    String location = response.headers().firstValue("Location")
                            .orElseThrow(() -> new PermanentFailure("HTTP " + status + " without Location"));
                    uri = redirect(uri, location);
                    continue;
                }
                if (status == 404 || status == 410) {
                    throw new PermanentFailure("HTTP " + status);
                }
                if (status != 200) {
                    throw new IOException("HTTP " + status);
                }
                String type = response.headers().firstValue("Content-Type")
                        .map(t -> t.split(";", 2)[0].strip().toLowerCase(Locale.ROOT)).orElse("");
                if (!type.startsWith("image/") || type.equals("image/svg+xml")) {
                    throw new PermanentFailure("not a supported image: '" + type + "'");
                }
                long max = properties.maxSize().toBytes();
                if (response.headers().firstValueAsLong("Content-Length").orElse(0) > max) {
                    throw new PermanentFailure("larger than " + max + " bytes");
                }
                BlobRef blob = blobs.put(new LimitedInputStream(body, max));
                if (blob.sizeBytes() == 0) {
                    throw new IOException("empty body");
                }
                return new Image(type, blob);
            }
        }
        throw new PermanentFailure("more than " + MAX_REDIRECTS + " redirects");
    }

    private void checkAddress(URI uri) throws IOException {
        if (properties.allowPrivateAddresses()) {
            return;
        }
        for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
            if (isPrivate(address)) {
                throw new PermanentFailure("address not allowed: " + address.getHostAddress());
            }
        }
    }

    static boolean isPrivate(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] b = address.getAddress();
        if (b.length == 4) {
            // 0.0.0.0/8 and the carrier-grade NAT range 100.64.0.0/10
            return b[0] == 0 || ((b[0] & 0xff) == 100 && (b[1] & 0xc0) == 64);
        }
        return (b[0] & 0xfe) == 0xfc; // unique local fc00::/7
    }

    /** An absolute http(s) URI from a URL as pages write it (see {@link #encode}). */
    static URI toUri(String url) throws PermanentFailure {
        return checkHttp(parse(url), url);
    }

    /** The target of a redirect, relative to the request URI. */
    static URI redirect(URI from, String location) throws PermanentFailure {
        return checkHttp(from.resolve(parse(location)), location);
    }

    private static URI parse(String url) throws PermanentFailure {
        try {
            return new URI(encode(url.strip()));
        } catch (URISyntaxException e) {
            throw new PermanentFailure("invalid URL: " + url);
        }
    }

    private static URI checkHttp(URI uri, String url) throws PermanentFailure {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https") || uri.getHost() == null) {
            throw new PermanentFailure("not an http(s) URL: " + url);
        }
        return uri;
    }

    /** Percent-encodes what a URI does not allow but pages still write (spaces, non-ASCII letters, …). */
    private static String encode(String url) {
        StringBuilder encoded = new StringBuilder();
        for (byte b : url.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if (c <= 0x20 || c >= 0x7f || UNSAFE.indexOf(c) >= 0) {
                encoded.append('%').append(String.format("%02X", c));
            } else {
                encoded.append((char) c);
            }
        }
        return encoded.toString();
    }

    /** Fails once more than {@code max} bytes were read. */
    private static final class LimitedInputStream extends FilterInputStream {
        private final long max;
        private long count;

        LimitedInputStream(InputStream in, long max) {
            super(in);
            this.max = max;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                count(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = super.read(buffer, offset, length);
            if (n > 0) {
                count(n);
            }
            return n;
        }

        private void count(int n) throws PermanentFailure {
            count += n;
            if (count > max) {
                throw new PermanentFailure("larger than " + max + " bytes");
            }
        }
    }
}
