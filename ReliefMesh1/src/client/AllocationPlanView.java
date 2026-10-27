package client;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

public class AllocationPlanView extends BorderPane {

    private static final String API_URL =
        "http://localhost:8081/ReliefMesh1/api/coordination/allocation-plan";

    private final HttpClient httpClient = HttpClient.newHttpClient();

    private final ObservableList<String[]> rows =
        FXCollections.observableArrayList();

    private final TableView<String[]> table = new TableView<>();

    private final Label status =
        new Label("Waiting for allocation plan...");

    private final Label summary = new Label();

    public AllocationPlanView(Runnable onBack) { {
        setPadding(new Insets(24));

        Label title = new Label("Coordination Allocation Plan");
        title.setStyle(
            "-fx-font-size: 25px; -fx-font-weight: bold;"
        );

        Label subtitle = new Label(
            "Shared-stock proposals ranked by request severity. "
            + "This plan is advisory and does not change the database."
        );
        subtitle.setWrapText(true);

        Button refresh = new Button("Refresh Allocation Plan");
        refresh.setOnAction(event -> loadPlan());

        Button back = new Button("Back to Adaptive Coordination");
        back.setOnAction(event -> onBack.run());

        HBox actions = new HBox(10, refresh, back);

        VBox header = new VBox(
            12, title, subtitle, summary, actions, status
        );
        header.setPadding(new Insets(0, 0, 18, 0));

        configureTable();

        Label safetyNote = new Label(
            "ADVISORY ONLY: A proposed quantity is not an approved "
            + "delivery. No stock is deducted, no vehicle is assigned, "
            + "and no active commitment is changed by this screen. "
            + "The server must revalidate stock and vehicle availability "
            + "before any delivery is created."
        );
        safetyNote.setWrapText(true);
        safetyNote.setPadding(new Insets(14, 0, 0, 0));

        setTop(header);
        setCenter(table);
        setBottom(safetyNote);

        loadPlan();}
    }

    private void configureTable() {
        addColumn("Request", 0);
        addColumn("Requester", 1);
        addColumn("Location", 2);
        addColumn("Severity", 3);
        addColumn("Resource", 4);
        addColumn("Outstanding", 5);
        addColumn("Proposed", 6);
        addColumn("Outcome", 7);
        addColumn("Explanation", 8);

        table.setItems(rows);
        table.setColumnResizePolicy(
            TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN
        );
        table.setPlaceholder(
            new Label("No allocation items returned by the server.")
        );
        table.setPrefHeight(480);
    }

    private void addColumn(String heading, int index) {
        TableColumn<String[], String> column =
            new TableColumn<>(heading);

        column.setCellValueFactory(data ->
            new SimpleStringProperty(data.getValue()[index])
        );

        table.getColumns().add(column);
    }

    private void loadPlan() {
        status.setText("Loading latest plan from ReliefMesh server...");
        summary.setText("");

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(API_URL))
            .timeout(Duration.ofSeconds(10))
            .header("Accept", "application/json")
            .GET()
            .build();

        httpClient.sendAsync(
            request, HttpResponse.BodyHandlers.ofString()
        ).whenComplete((response, error) -> {
            if (error != null) {
                Platform.runLater(() -> {
                    status.setText(
                        "Connection failed. Check Tomcat and the API URL."
                    );
                    System.err.println(
                        "Allocation plan connection error: " + error
                    );
                });
                return;
            }

            if (response.statusCode() != 200
                    || response.body().contains("\"error\"")) {
                Platform.runLater(() -> {
                    status.setText(
                        "Allocation API failed. HTTP "
                        + response.statusCode()
                    );
                    System.err.println(
                        "Allocation API response: " + response.body()
                    );
                });
                return;
            }

            String json = response.body();

            int vehicles = readNumber(json, "availableVehicles");
            int capacity = readNumber(
                json, "largestAvailableVehicleCapacity"
            );
            boolean advisory = json.contains("\"advisoryOnly\":true");

            ObservableList<String[]> loaded =
                FXCollections.observableArrayList();

            // Each item object in this API contains no nested objects.
            Pattern objectPattern =
                Pattern.compile("\\{([^{}]*)\\}");

            Matcher objectMatcher = objectPattern.matcher(json);

            while (objectMatcher.find()) {
                String object = objectMatcher.group(1);

                if (!object.contains("\"requestId\"")) {
                    continue;
                }

                loaded.add(new String[] {
                    value(object, "requestId"),
                    value(object, "requesterName"),
                    value(object, "location"),
                    value(object, "severity"),
                    value(object, "resourceName"),
                    value(object, "outstandingQuantity"),
                    value(object, "proposedQuantity"),
                    value(object, "outcome"),
                    value(object, "explanation")
                });
            }

            Platform.runLater(() -> {
                rows.setAll(loaded);

                summary.setText(
                    "Available vehicles: " + vehicles
                    + " | Largest available capacity: " + capacity
                    + " | Mode: "
                    + (advisory ? "ADVISORY ONLY" : "Check API response")
                );

                status.setText(
                    "Connected. Loaded " + loaded.size()
                    + " allocation item(s)."
                );
            });
        });
    }

    private String value(String json, String key) {
        String stringPattern =
            "\"" + Pattern.quote(key)
            + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"";

        Matcher stringMatcher =
            Pattern.compile(stringPattern).matcher(json);

        if (stringMatcher.find()) {
            return unescape(stringMatcher.group(1));
        }

        return Integer.toString(readNumber(json, key));
    }

    private int readNumber(String json, String key) {
        Matcher matcher = Pattern.compile(
            "\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?\\d+)"
        ).matcher(json);

        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }

        return 0;
    }

    private String unescape(String value) {
        return value
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\");
    }

    @SuppressWarnings("exports")
	public Parent getView() {
        return this;
    }
}