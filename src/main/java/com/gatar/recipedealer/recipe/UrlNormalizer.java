package com.gatar.recipedealer.recipe;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Canonical form of a recipe page URL, used as identity key. Bump {@link #VERSION} on any rule change; keys store
 * the raw value and the version so they can be recomputed.
 *
 * <p>Rules (v1): http and https are equal (https); host lower-case without {@code www.}, {@code m.} is kept;
 * {@code amp.} host prefix, {@code /amp} path suffix, {@code amp} / {@code outputType=amp} parameters and Google AMP
 * cache URLs resolve to the original page; default port dropped; trailing slash dropped; tracking parameters
 * ({@code utm_*}, click ids, ...) removed, remaining parameters sorted; fragment dropped.
 */
public final class UrlNormalizer {

    public static final int VERSION = 1;

    private static final Set<String> TRACKING_PARAMS = Set.of(
            "fbclid", "gclid", "dclid", "gbraid", "wbraid", "msclkid", "yclid", "twclid", "ttclid", "li_fat_id",
            "mc_cid", "mc_eid", "igshid", "igsh", "_ga", "_gl", "_hsenc", "_hsmi", "mkt_tok", "ref_src", "ref_url",
            "spm", "si", "s_cid", "cmpid", "ito", "oly_anon_id", "oly_enc_id", "vero_id", "wickedid");
    private static final Pattern GOOGLE_AMP_VIEWER = Pattern.compile("^/amp/s/(.+)$");
    private static final Pattern AMP_CACHE = Pattern.compile("^/[cvi]/(?:s/)?(.+)$");

    private UrlNormalizer() {
    }

    /** @return the canonical URL, or empty if the input is not an absolute http(s) URL */
    public static Optional<String> normalize(String raw) {
        return normalize(raw, false);
    }

    /** Like {@link #normalize(String)} but keeps the fragment (JSON-LD {@code @id} values such as {@code ...#recipe}). */
    public static Optional<String> normalizeKeepingFragment(String raw) {
        return normalize(raw, true);
    }

    private static Optional<String> normalize(String raw, boolean keepFragment) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        URI uri;
        try {
            uri = new URI(raw.strip()).normalize();
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https") || uri.getHost() == null) {
            return Optional.empty();
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();

        // Google AMP viewer and AMP cache URLs wrap the original URL in the path.
        if ((host.equals("google.com") || host.equals("www.google.com")) && GOOGLE_AMP_VIEWER.matcher(path).matches()) {
            return normalize("https://" + GOOGLE_AMP_VIEWER.matcher(path).replaceFirst("$1") + rawQuerySuffix(uri), keepFragment);
        }
        if (host.endsWith(".cdn.ampproject.org") && AMP_CACHE.matcher(path).matches()) {
            return normalize("https://" + AMP_CACHE.matcher(path).replaceFirst("$1") + rawQuerySuffix(uri), keepFragment);
        }

        if (host.startsWith("www.")) {
            host = host.substring(4);
        }
        if (host.startsWith("amp.")) {
            host = host.substring(4);
        }
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }

        path = path.replaceAll("/{2,}", "/");
        if (path.endsWith("/amp") || path.endsWith("/amp/")) {
            path = path.substring(0, path.lastIndexOf("/amp"));
        } else if (path.endsWith(".amp")) {
            path = path.substring(0, path.length() - 4);
        }
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }

        String port = uri.getPort() == -1 || uri.getPort() == 80 || uri.getPort() == 443 ? "" : ":" + uri.getPort();
        String query = cleanQuery(uri.getRawQuery());
        String fragment = keepFragment && uri.getRawFragment() != null && !uri.getRawFragment().isEmpty()
                ? "#" + uri.getRawFragment() : "";
        return Optional.of("https://" + host + port + path + (query.isEmpty() ? "" : "?" + query) + fragment);
    }

    private static String rawQuerySuffix(URI uri) {
        return uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
    }

    private static String cleanQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return "";
        }
        List<String> kept = new ArrayList<>();
        for (String param : rawQuery.split("&")) {
            if (param.isEmpty()) {
                continue;
            }
            int eq = param.indexOf('=');
            String name = (eq < 0 ? param : param.substring(0, eq)).toLowerCase(Locale.ROOT);
            String value = eq < 0 ? "" : param.substring(eq + 1);
            boolean amp = name.equals("amp") || (name.equals("outputtype") && value.equalsIgnoreCase("amp"));
            if (name.startsWith("utm_") || TRACKING_PARAMS.contains(name) || amp) {
                continue;
            }
            kept.add(param);
        }
        kept.sort(Comparator.naturalOrder());
        return String.join("&", kept);
    }
}
