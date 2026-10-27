package client;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javafx.application.Platform;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

public class CoordinationView extends BorderPane {

    private static final String API_URL =
        "http://localhost:8081/ReliefMesh1/api/coordination";

    private final HttpClient client = HttpClient.newHttpClient();

    private final ObservableList<ResourceRow> rows =
        FXCollections.observableArrayList();

    private final TableView<ResourceRow> table = new TableView<>();

    private final Label status =
        new Label("Loading coordination data...");

    private final Label vehicleSummary = new Label();

    private final Label overview = new Label();

    public CoordinationView() {
        setPadding(new Insets(24));

        Label title = new Label("Adaptive Coordination");
        title.setStyle(
            "-fx-font-size: 25px; -fx-font-weight: bold;"
        );

        Label subtitle = new Label(
            "Monitor relief demand, inventory, existing commitments, "
            + "shortages, and feasible resource allocation."
        );
        subtitle.setWrapText(true);

        Button refresh = new Button("Refresh Coordination");
        refresh.setOnAction(event -> loadData());

        HBox headerActions = new HBox(12, refresh, status);

        VBox header = new VBox(
            10, title, subtitle, overview, vehicleSummary, headerActions
        );
        header.setPadding(new Insets(0, 0, 20, 0));

        configureTable();

        Label note = new Label(
            "Important: Recommendations are advisory only. "
            + "They do not reserve stock, create deliveries, "
            + "or cancel existing commitments. Verify shortages "
            + "and obtain approval before changing active deliveries."
        );
        note.setWrapText(true);
        note.setPadding(new Insets(16, 0, 0, 0));

        setTop(header);
        setCenter(table);
        setBottom(note);

        loadData();
    }

    private void configureTable() {
        TableColumn<ResourceRow, String> resource =
            new TableColumn<>("Resource");
        resource.setCellValueFactory(
            data -> data.getValue().resourceNameProperty()
        );
        resource.setMinWidth(110);

        TableColumn<ResourceRow, Number> demand =
            new TableColumn<>("Pending Demand");
        demand.setCellValueFactory(
            data -> data.getValue().demandProperty()
        );
        demand.setMinWidth(105);

        TableColumn<ResourceRow, Number> stock =
            new TableColumn<>("Physical Stock");
        stock.setCellValueFactory(
            data -> data.getValue().stockProperty()
        );
        stock.setMinWidth(100);

        TableColumn<ResourceRow, Number> reserved =
            new TableColumn<>("Reserved");
        reserved.setCellValueFactory(
            data -> data.getValue().reservedProperty()
        );
        reserved.setMinWidth(80);

        TableColumn<ResourceRow, Number> available =
            new TableColumn<>("Unreserved Stock");
        available.setCellValueFactory(
            data -> data.getValue().unreservedProperty()
        );
        available.setMinWidth(110);

        TableColumn<ResourceRow, Number> severity =
            new TableColumn<>("Severity");
        severity.setCellValueFactory(
            data -> data.getValue().severityProperty()
        );
        severity.setMinWidth(75);
        severity.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(Number value, boolean empty) {
                super.updateItem(value, empty);

                if (empty || value == null) {
                    setText(null);
                    setStyle("");
                } else {
                    int level = value.intValue();
                    setText(level + " / 5");

                    if (level >= 4) {
                        setStyle(
                            "-fx-font-weight: bold; "
                            + "-fx-text-fill: #b91c1c;"
                        );
                    } else if (level == 3) {
                        setStyle(
                            "-fx-font-weight: bold; "
                            + "-fx-text-fill: #b45309;"
                        );
                    } else {
                        setStyle("");
                    }
                }
            }
        });

        TableColumn<ResourceRow, Number> shortage =
            new TableColumn<>("Shortage");
        shortage.setCellValueFactory(
            data -> data.getValue().shortageProperty()
        );
        shortage.setMinWidth(80);
        shortage.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(Number value, boolean empty) {
                super.updateItem(value, empty);

                if (empty || value == null) {
                    setText(null);
                    setStyle("");
                } else {
                    int amount = value.intValue();
                    setText(String.valueOf(amount));

                    if (amount > 0) {
                        setStyle(
                            "-fx-font-weight: bold; "
                            + "-fx-text-fill: #b91c1c;"
                        );
                    } else {
                        setStyle(
                            "-fx-text-fill: #15803d;"
                        );
                    }
                }
            }
        });

        TableColumn<ResourceRow, Number> feasible =
            new TableColumn<>("Feasible Now");
        feasible.setCellValueFactory(
            data -> data.getValue().feasibleProperty()
        );
        feasible.setMinWidth(90);

        TableColumn<ResourceRow, String> recommendation =
            new TableColumn<>("Recommendation");
        recommendation.setCellValueFactory(
            data -> data.getValue().recommendationProperty()
        );
        recommendation.setMinWidth(250);
        recommendation.setPrefWidth(340);
        recommendation.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);

                if (empty || value == null || value.isBlank()) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(formatRecommendation(value));
                    setWrapText(true);

                    if (value.toLowerCase().contains("shortage")
                            || value.toLowerCase().contains("insufficient")
                            || value.toLowerCase().contains("no stock")) {
                        setStyle(
                            "-fx-text-fill: #b91c1c; "
                            + "-fx-font-weight: bold;"
                        );
                    } else {
                        setStyle("-fx-text-fill: #166534;");
                    }
                }
            }
        });

        table.getColumns().addAll(
            resource, demand, stock, reserved, available,
            severity, shortage, feasible, recommendation
        );

        table.setItems(rows);
        table.setColumnResizePolicy(
            TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN
        );
        table.setPlaceholder(
            new Label("No resource recommendations available.")
        );
        table.setFixedCellSize(-1);
        table.setMinHeight(300);
    }

    private String formatRecommendation(String value) {
        String text = value.replace('_', ' ').trim();

        if (text.isEmpty()) {
            return "No recommendation supplied";
        }

        String lower = text.toLowerCase();

        if (lower.contains("no unreserved stock")) {
            return "STOCK SHORTAGE — Find another warehouse "
                + "or replenish inventory.";
        }

        if (lower.contains("insufficient")) {
            return "INSUFFICIENT STOCK — Review inventory "
                + "and existing commitments.";
        }

        if (lower.contains("shortage")) {
            return "STOCK SHORTAGE — " + text;
        }

        if (lower.contains("check data")
                || lower.contains("conflict")) {
            return "CHECK DATA — " + text;
        }

        if (lower.contains("available")) {
            return "STOCK AVAILABLE — " + text;
        }

        return text.substring(0, 1).toUpperCase()
            + text.substring(1);
    }

    private void loadData() {
        status.setText("Connecting...");
        overview.setText("Retrieving current resource analysis...");
        
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(API_URL))
            .GET()
            .build();

        client.sendAsync(
            request, HttpResponse.BodyHandlers.ofString()
        ).thenAccept(response -> {
            if (response.statusCode() != 200) {
                throw new RuntimeException(
                    "Server returned HTTP " + response.statusCode()
                );
            }

            String json = response.body();

            if (json.contains("\"error\"")) {
                throw new RuntimeException(
                    readString(json, "error")
                );
            }

            int vehicleCount = readNumber(
                json, "availableVehicles"
            );

            // Support either API field name.
            int maxCapacity = readNumber(
                json, "largestAvailableVehicleCapacity"
            );

            if (maxCapacity == 0) {
                maxCapacity = readNumber(
                    json, "largestVehicleCapacity"
                );
            }

            ObservableList<ResourceRow> loaded =
                FXCollections.observableArrayList();

            Pattern objectPattern =
                Pattern.compile("\\{([^{}]*)\\}");

            Matcher objectMatcher =
                objectPattern.matcher(json);

            while (objectMatcher.find()) {
                String object = objectMatcher.group(1);

                if (!object.contains("\"resourceName\"")) {
                    continue;
                }

                loaded.add(new ResourceRow(
                    readString(object, "resourceName"),
                    readNumber(object, "pendingDemand"),
                    readNumber(object, "physicalStock"),
                    readNumber(object, "reservedQuantity"),
                    readNumber(object, "unreservedStock"),
                    readNumber(object, "highestSeverity"),
                    readNumber(object, "shortage"),
                    readNumber(object, "feasibleQuantity"),
                    readString(object, "recommendation")
                ));
            }

            final int finalMaxCapacity = maxCapacity;

            Platform.runLater(() -> {
                rows.setAll(loaded);

                vehicleSummary.setText(
                    "Available vehicles: " + vehicleCount
                    + "     |     Largest available capacity: "
                    + finalMaxCapacity
                );

                long shortageCount = loaded.stream()
                    .filter(row -> row.getShortage() > 0)
                    .count();

                overview.setText(
                    "Resources monitored: " + loaded.size()
                    + "     |     Resources with shortages: "
                    + shortageCount
                );

                status.setText(
                    "Connected | " + loaded.size()
                    + " resource recommendation(s) loaded."
                );
            });

        }).exceptionally(error -> {
            Throwable cause = error.getCause() != null
                ? error.getCause() : error;

            Platform.runLater(() -> {
                status.setText("Connection failed");
                overview.setText(
                    "Could not load coordination data: "
                    + cause.getMessage()
                );
            });

            return null;
        });
    }

    private int readNumber(String json, String key) {
        Pattern pattern = Pattern.compile(
            "\"" + Pattern.quote(key)
            + "\"\\s*:\\s*(-?\\d+)"
        );

        Matcher matcher = pattern.matcher(json);

        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }

        return 0;
    }

    private String readString(String json, String key) {
        Pattern pattern = Pattern.compile(
            "\"" + Pattern.quote(key)
            + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\""
        );

        Matcher matcher = pattern.matcher(json);

        if (!matcher.find()) {
            return "";
        }

        return matcher.group(1)
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\");
    }

    public Parent getView() {
        return this;
    }

    public static class ResourceRow {
        private final SimpleStringProperty resourceName;
        private final SimpleIntegerProperty demand;
        private final SimpleIntegerProperty stock;
        private final SimpleIntegerProperty reserved;
        private final SimpleIntegerProperty unreserved;
        private final SimpleIntegerProperty severity;
        private final SimpleIntegerProperty shortage;
        private final SimpleIntegerProperty feasible;
        private final SimpleStringProperty recommendation;

        public ResourceRow(
                String resourceName,
                int demand,
                int stock,
                int reserved,
                int unreserved,
                int severity,
                int shortage,
                int feasible,
                String recommendation) {

            this.resourceName =
                new SimpleStringProperty(resourceName);
            this.demand = new SimpleIntegerProperty(demand);
            this.stock = new SimpleIntegerProperty(stock);
            this.reserved = new SimpleIntegerProperty(reserved);
            this.unreserved = new SimpleIntegerProperty(unreserved);
            this.severity = new SimpleIntegerProperty(severity);
            this.shortage = new SimpleIntegerProperty(shortage);
            this.feasible = new SimpleIntegerProperty(feasible);
            this.recommendation =
                new SimpleStringProperty(recommendation);
        }

        public int getShortage() {
            return shortage.get();
        }

        public SimpleStringProperty resourceNameProperty() {
            return resourceName;
        }

        public SimpleIntegerProperty demandProperty() {
            return demand;
        }

        public SimpleIntegerProperty stockProperty() {
            return stock;
        }

        public SimpleIntegerProperty reservedProperty() {
            return reserved;
        }

        public SimpleIntegerProperty unreservedProperty() {
            return unreserved;
        }

        public SimpleIntegerProperty severityProperty() {
            return severity;
        }

        public SimpleIntegerProperty shortageProperty() {
            return shortage;
        }

        public SimpleIntegerProperty feasibleProperty() {
            return feasible;
        }

        public SimpleStringProperty recommendationProperty() {
            return recommendation;
        }
    }
}