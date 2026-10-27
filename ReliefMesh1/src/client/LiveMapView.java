
package client;

import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

public class LiveMapView extends BorderPane {

    private static final String API =
        "http://localhost:8081/ReliefMesh1/api/map-data";

    private final WebView webView = new WebView();
    private final WebEngine engine = webView.getEngine();
    private final Label status = new Label("Loading map...");
    private final Button refresh = new Button("Refresh Map");

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    private boolean pageReady = false;
    private String pendingJson;

    public LiveMapView() {
        setPadding(new Insets(10));

        Label title = new Label("ReliefMesh - Live Operations Map");
        title.setStyle(
            "-fx-font-size: 20px;" +
            "-fx-font-weight: bold;" +
            "-fx-text-fill: #12304a;"
        );

        refresh.setOnAction(e -> loadMapData());

        HBox toolbar = new HBox(15, title, refresh, status);
        toolbar.setPadding(new Insets(10));

        webView.setMinSize(500, 400);
        webView.setPrefSize(1100, 650);

        setTop(toolbar);
        setCenter(webView);
        widthProperty().addListener((obs, oldValue, newValue) -> {
            if (pageReady) {
            	engine.executeScript(
            		    "if (window.reliefMap) window.reliefMap.invalidateSize(true);"
            		);
            }
        });

        heightProperty().addListener((obs, oldValue, newValue) -> {
            if (pageReady) {
                engine.executeScript(
                    "if (window.mapReady) map.invalidateSize(true);"
                );
            }
        });
        engine.getLoadWorker().stateProperty().addListener(
            (observable, oldState, newState) -> {
                if (newState == Worker.State.SUCCEEDED) {
                    pageReady = true;

                    if (pendingJson != null) {
                        renderJson(pendingJson);
                    } else {
                        loadMapData();
                    }
                } else if (newState == Worker.State.FAILED) {
                    pageReady = false;
                    status.setText(
                        "Map page failed. Check internet access."
                    );
                }
            }
        );

        loadMapPage();
    }

    private void loadMapPage() {
        pageReady = false;
        status.setText("Loading satellite map...");

        String html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport"
                      content="width=device-width, initial-scale=1">

                <link rel="stylesheet"
                      href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css">

                <style>
                    html, body, #map {
                        height: 100%;
                        width: 100%;
                        margin: 0;
                    }

                    .legend {
                        background: white;
                        padding: 10px;
                        line-height: 1.8;
                        border-radius: 5px;
                        box-shadow: 0 1px 5px #777;
                        font-size: 12px;
                    }

                    .unmapped {
                        background: white;
                        padding: 8px;
                        max-width: 230px;
                        max-height: 150px;
                        overflow-y: auto;
                        font-size: 12px;
                    }
                </style>
            </head>

            <body>
                <div id="map"></div>

                <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>

                <script>
                    window.mapReady = false;

                    if (typeof L !== 'undefined') {
                        const map = L.map('map')
                            .setView([17.9689, 79.5941], 12);
window.reliefMap = map;
                        L.tileLayer(
                            'https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}',
                            {
                                maxZoom: 19,
                                attribution: 'Tiles &copy; Esri'
                            }
                        ).addTo(map);

                        const overlays = L.layerGroup().addTo(map);
                        function fixMapSize() {
    if (window.mapReady) {
        map.invalidateSize(true);
    }
}

map.whenReady(function() {
    setTimeout(fixMapSize, 300);
    setTimeout(fixMapSize, 1000);
});

window.addEventListener('resize', function() {
    setTimeout(fixMapSize, 150);
});

                        const legend = L.control({ position: 'topright' });

                        legend.onAdd = function() {
                            const div = L.DomUtil.create('div', 'legend');
                            div.innerHTML =
                                '<b>ReliefMesh Legend</b><br>' +
                                '<span style="color:blue">●</span> Warehouse<br>' +
                                '<span style="color:red">●</span> Critical request<br>' +
                                '<span style="color:orange">●</span> Other request<br>' +
                                '<span style="color:purple">●</span> Vehicle<br>' +
                                '<span style="color:limegreen">━</span> Road / delivery route';
                            return div;
                        };

                        legend.addTo(map);

                        function escapeHtml(value) {
                            return String(value ?? '').replace(/[&<>"']/g,
                                function(c) {
                                    return {
                                        '&': '&amp;',
                                        '<': '&lt;',
                                        '>': '&gt;',
                                        '"': '&quot;',
                                        "'": '&#39;'
                                    }[c];
                                });
                        }

                        function popup(title, details) {
                            return '<b>' + escapeHtml(title) + '</b><br>' +
                                   details.map(function(item) {
                                       return escapeHtml(item[0]) + ': ' +
                                              escapeHtml(item[1]);
                                   }).join('<br>');
                        }

                        function addPoint(node, color, title, details) {
                            const marker = L.circleMarker(
                                [Number(node.latitude), Number(node.longitude)],
                                {
                                    radius: 8,
                                    color: color,
                                    fillColor: color,
                                    fillOpacity: 0.9,
                                    weight: 2
                                }
                            );

                            marker.bindPopup(popup(title, details));
                            marker.addTo(overlays);
                            return marker;
                        }

                        function validNode(node) {
                            return node &&
                                Number.isFinite(Number(node.latitude)) &&
                                Number.isFinite(Number(node.longitude)) &&
                                Math.abs(Number(node.latitude)) <= 90 &&
                                Math.abs(Number(node.longitude)) <= 180;
                        }

                        window.renderMapData = function(data) {
                            overlays.clearLayers();

                            const nodes = data.nodes || [];
                            const warehouses = data.warehouses || [];
                            const requests = data.requests || [];
                            const vehicles = data.vehicles || [];
                            const deliveries = data.deliveries || [];
                            const edges = data.edges || [];

                            const nodeById = {};
                            const nodeByName = {};
                            const markers = [];

                            nodes.forEach(function(node) {
                                nodeById[Number(node.nodeId)] = node;
                                nodeByName[String(node.name).trim().toLowerCase()] = node;
                            });

                            // Draw road-network edges from the database.
                            edges.forEach(function(edge) {
                                const from = nodeById[Number(edge.fromNode)];
                                const to = nodeById[Number(edge.toNode)];

                                if (!from || !to || !validNode(from) ||
                                    !validNode(to)) {
                                    return;
                                }

                                const line = L.polyline([
                                    [Number(from.latitude), Number(from.longitude)],
                                    [Number(to.latitude), Number(to.longitude)]
                                ], {
                                    color: '#00e676',
                                    weight: 3,
                                    opacity: 0.8
                                }).addTo(overlays);

                                line.bindPopup(
                                    escapeHtml(from.name) + ' to ' +
                                    escapeHtml(to.name) + '<br>Distance: ' +
                                    escapeHtml(edge.distanceKm) + ' km'
                                );
                            });

                            // Warehouses: match their location to a route node.
                            warehouses.forEach(function(warehouse) {
                                const key = String(warehouse.location || '')
                                    .trim().toLowerCase();
                                const node = nodeByName[key];

                                if (!node || !validNode(node)) return;

                                markers.push(addPoint(node, 'blue',
                                    warehouse.location, [
                                        ['Resource', warehouse.resourceName],
                                        ['Available quantity', warehouse.quantity],
                                        ['Resource ID', warehouse.resourceId]
                                    ]));
                            });

                            // Requests: only map locations with known coordinates.
                            const unmapped = [];

                            requests.forEach(function(request) {
                                const key = String(request.location || '')
                                    .trim().toLowerCase();
                                const node = nodeByName[key];

                                if (!node || !validNode(node)) {
                                    unmapped.push(
                                        'Request #' + request.requestId + ': ' +
                                        (request.location || 'Unknown location')
                                    );
                                    return;
                                }

                                const critical = Number(request.severity) >= 5;
                                markers.push(addPoint(node,
                                    critical ? 'red' : 'orange',
                                    'Request #' + request.requestId, [
                                        ['Requester', request.requesterName],
                                        ['Location', request.location],
                                        ['Emergency', request.emergencyType],
                                        ['Severity', request.severity],
                                        ['People affected', request.peopleAffected],
                                        ['Status', request.status]
                                    ]));
                            });

                            // Vehicles: show them at their known current locations.
                            vehicles.forEach(function(vehicle) {
                                const key = String(vehicle.location || '')
                                    .trim().toLowerCase();
                                const node = nodeByName[key];

                                if (!node || !validNode(node)) return;

                                markers.push(addPoint(node, 'purple',
                                    vehicle.vehicleName, [
                                        ['Vehicle ID', vehicle.vehicleId],
                                        ['Capacity', vehicle.capacity],
                                        ['Status', vehicle.status],
                                        ['Location', vehicle.location]
                                    ]));
                            });

                            // Delivery records: connect known source and destination
                            // locations when both are available. This is a display
                            // connection, not an optimized route calculation.
                            deliveries.forEach(function(delivery) {
                                const request = requests.find(function(r) {
                                    return Number(r.requestId) ===
                                           Number(delivery.requestId);
                                });

                                if (!request) return;

                                const destination = nodeByName[
                                    String(request.location || '')
                                        .trim().toLowerCase()
                                ];

                                const sourceResource = warehouses.find(function(w) {
                                    return delivery.sourceWarehouseId != null &&
                                           Number(w.resourceId) ===
                                           Number(delivery.sourceWarehouseId);
                                });

                                const sourceName = sourceResource
                                    ? sourceResource.location
                                    : null;

                                const source = sourceName
                                    ? nodeByName[String(sourceName).trim().toLowerCase()]
                                    : null;

                                if (!source || !destination ||
                                    !validNode(source) || !validNode(destination)) {
                                    return;
                                }

                                const delivered = String(delivery.status)
                                    .toUpperCase() === 'DELIVERED';

                                L.polyline([
                                    [Number(source.latitude), Number(source.longitude)],
                                    [Number(destination.latitude), Number(destination.longitude)]
                                ], {
                                    color: delivered ? '#00ff00' : '#ffeb3b',
                                    weight: 4,
                                    opacity: 0.9,
                                    dashArray: delivered ? null : '8, 8'
                                }).bindPopup(
                                    'Delivery #' + escapeHtml(delivery.deliveryId) +
                                    '<br>Request #' + escapeHtml(delivery.requestId) +
                                    '<br>Resource: ' + escapeHtml(delivery.resourceName) +
                                    '<br>Quantity: ' + escapeHtml(delivery.quantity) +
                                    '<br>Status: ' + escapeHtml(delivery.status)
                                ).addTo(overlays);
                            });

                            // Report locations that cannot be mapped accurately.
                            if (unmapped.length > 0) {
                                const control = L.control({ position: 'bottomleft' });

                                control.onAdd = function() {
                                    const div = L.DomUtil.create('div', 'unmapped');
                                    div.innerHTML = '<b>Unmapped requests</b><br>' +
                                        unmapped.map(escapeHtml).join('<br>');
                                    return div;
                                };

                                control.addTo(map);
                                window.currentUnmappedControl = control;
                            }

                            // Remove the previous unmapped-location box on refresh.
                            // It is managed separately from the map layers.
                            if (window.previousUnmappedControl) {
                                map.removeControl(window.previousUnmappedControl);
                            }

                            window.previousUnmappedControl =
                                window.currentUnmappedControl || null;
                            window.currentUnmappedControl = null;

                            if (markers.length > 0) {
                                const group = L.featureGroup(markers);
                                map.fitBounds(group.getBounds().pad(0.15), {
                                    maxZoom: 14
                                });
                            }

                            window.mapReady = true;
                        };

                        window.renderData = function(data) {
                            if (window.mapReady && window.renderMapData) {
                                window.renderMapData(data);
                            }
                        };

                        window.mapReady = true;
                        document.body.setAttribute('data-map-ready', 'true');
                    } else {
                        document.body.innerText =
                            'Leaflet did not load. Check internet access.';
                    }
                </script>
            </body>
            </html>
            """;

        engine.loadContent(html);
    }

    private void loadMapData() {
        refresh.setDisable(true);
        status.setText("Fetching database records...");

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(API))
            .timeout(Duration.ofSeconds(15))
            .header("Accept", "application/json")
            .GET()
            .build();

        http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .whenComplete((response, error) ->
                Platform.runLater(() -> {
                    refresh.setDisable(false);

                    if (error != null) {
                        status.setText("API connection failed. Check Tomcat.");
                        return;
                    }

                    if (response.statusCode() != 200) {
                        status.setText(
                            "Map API returned HTTP " + response.statusCode()
                        );
                        return;
                    }

                    String json = response.body();

                    if (json == null || !json.trim().startsWith("{")) {
                        status.setText("Invalid JSON returned by map API.");
                        return;
                    }

                    pendingJson = json;

                    if (pageReady) {
                        renderJson(json);
                    } else {
                        status.setText("Waiting for map page...");
                    }
                })
            );
    }

    private void renderJson(String json) {
        if (!pageReady) {
            pendingJson = json;
            return;
        }

        // Base64 safely transports the JSON into JavaScript.
        String encoded = Base64.getEncoder().encodeToString(
            json.getBytes(StandardCharsets.UTF_8)
        );

        try {
            Object result = engine.executeScript(
                "window.renderData(JSON.parse(" +
                "decodeURIComponent(escape(atob('" + encoded + "')))" +
                ")); 'ok';"
            );

            if ("ok".equals(result)) {
                pendingJson = null;
                status.setText("Map updated from database records.");
            } else {
                status.setText("Could not render map data.");
            }
        } catch (Exception ex) {
            status.setText("Map rendering failed: " + ex.getMessage());
        }
    }
}
