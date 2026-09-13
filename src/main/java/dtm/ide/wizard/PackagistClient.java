package dtm.ide.wizard;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

final class PackagistClient {

    static final String SEARCH_URL = "https://packagist.org/search.json";
    static final String POPULAR_URL = "https://packagist.org/explore/popular.json";
    static final String METADATA_URL = "https://repo.packagist.org/p2/";
    private static final int PER_PAGE = 30;
    private static final String USER_AGENT = "PhpOrionSupport/1.0";

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    record Package(String name, String description, long downloads) {
        Package {
            name = name == null ? "" : name.trim();
            description = description == null ? "" : description.trim();
        }

        String vendor() {
            int slash = name.indexOf('/');
            return slash < 0 ? "" : name.substring(0, slash);
        }
    }

    List<Package> search(String query) {
        String trimmed = query == null ? "" : query.trim();
        return trimmed.isBlank() ? popular() : byQuery(trimmed);
    }

    List<Package> popular() {
        return parsePackages(fetch(POPULAR_URL + "?page=1"), "packages");
    }

    private List<Package> byQuery(String query) {
        String url = SEARCH_URL + "?q=" + encode(query) + "&per_page=" + PER_PAGE;
        return parsePackages(fetch(url), "results");
    }

    Optional<String> latestConstraint(String packageName) {
        if (packageName == null || packageName.isBlank()) {
            return Optional.empty();
        }
        JsonNode root = fetch(METADATA_URL + packageName.toLowerCase(Locale.ROOT) + ".json");
        if (root == null) {
            return Optional.empty();
        }
        JsonNode versions = root.path("packages").path(packageName.toLowerCase(Locale.ROOT));
        if (!versions.isArray()) {
            return Optional.empty();
        }
        for (JsonNode entry : versions) {
            String version = entry.path("version_normalized").asText("");
            String display = entry.path("version").asText("");
            if (display.startsWith("dev-") || display.contains("-dev")
                    || version.isBlank() || isPreRelease(display)) {
                continue;
            }
            return Optional.of(caretOf(display));
        }
        return Optional.empty();
    }

    static String caretOf(String version) {
        String cleaned = version.startsWith("v") ? version.substring(1) : version;
        String[] parts = cleaned.split("\\.");
        if (parts.length < 2) {
            return "^" + cleaned;
        }
        return "^" + parts[0] + "." + parts[1];
    }

    private static boolean isPreRelease(String version) {
        String lower = version.toLowerCase(Locale.ROOT);
        return lower.contains("alpha") || lower.contains("beta")
                || lower.contains("rc") || lower.contains("pre");
    }

    private List<Package> parsePackages(JsonNode root, String arrayField) {
        if (root == null) {
            return List.of();
        }
        JsonNode array = root.path(arrayField);
        if (!array.isArray()) {
            return List.of();
        }
        List<Package> packages = new ArrayList<>();
        for (JsonNode entry : array) {
            String name = entry.path("name").asText("");
            if (name.isBlank() || !name.contains("/")) {
                continue;
            }
            packages.add(new Package(name,
                    entry.path("description").asText(""),
                    entry.path("downloads").asLong(0)));
        }
        return List.copyOf(packages);
    }

    private JsonNode fetch(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return null;
            }
            return mapper.readTree(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
