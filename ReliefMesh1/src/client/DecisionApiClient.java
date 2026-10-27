package client;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public class DecisionApiClient {

    private static final String ENDPOINT =
        "http://localhost:8081/ReliefMesh1/api/coordination/decision";

    private final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    public String approve(
            int requestId,
            String resourceName,
            int quantity,
            int vehicleId,
            int warehouseId,
            String explanation) throws Exception {

        return send("approve", requestId, resourceName, quantity,
                    vehicleId, warehouseId, explanation);
    }

    public String reject(
            int requestId,
            String resourceName,
            int quantity,
            int vehicleId,
            int warehouseId,
            String explanation) throws Exception {

        return send("reject", requestId, resourceName, quantity,
                    vehicleId, warehouseId, explanation);
    }

    private String send(
            String action,
            int requestId,
            String resourceName,
            int quantity,
            int vehicleId,
            int warehouseId,
            String explanation) throws Exception {

        String body =
            "action=" + encode(action) +
            "&requestId=" + requestId +
            "&resourceName=" + encode(resourceName) +
            "&quantity=" + quantity +
            "&vehicleId=" + vehicleId +
            "&warehouseId=" + warehouseId +
            "&decidedBy=" + encode("operator") +
            "&explanation=" + encode(
                explanation == null ? "" : explanation);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(ENDPOINT))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type",
                    "application/x-www-form-urlencoded; charset=UTF-8")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        HttpResponse<String> response = client.send(
            request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() < 200 ||
            response.statusCode() >= 300) {
            throw new IllegalStateException(
                "Decision failed (HTTP " + response.statusCode() +
                "): " + response.body());
        }

        return response.body();
    }

    private String encode(String value) {
        return URLEncoder.encode(
            value == null ? "" : value,
            StandardCharsets.UTF_8);
    }
}