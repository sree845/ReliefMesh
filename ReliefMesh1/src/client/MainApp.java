package client;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.scene.web.WebView;
import javafx.scene.web.WebEngine;

public class MainApp extends Application {
    private BorderPane root;
    private VBox sidebar;
    private VBox content;
    private Button activeButton;

    private Label pendingValue, foodValue, medicalValue, vehiclesValue, connectionStatus;
    private TableView<String[]> deliveryTable, coordinationTable, decisionTable, allocationPreviewTable;
    private Label deliveryStatus, coordinationStatus, coordinationVehicles, decisionStatus, allocationPreviewStatus;
    private TableView<String[]> reliefRequestsTable;
    private Label reliefRequestsStatus;

    private final String navy = "#14243A";
    private final String blue = "#2563EB";
    private final String background = "#F3F6FB";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4)).build();

    private static final String BASE_URL = "http://localhost:8081/ReliefMesh1";
    private static final String DASHBOARD_API = BASE_URL + "/api/dashboard";
    private static final String DELIVERIES_API = BASE_URL + "/api/deliveries";
    private static final String COORDINATION_API = BASE_URL + "/api/coordination";
    private static final String ALLOCATION_PLAN_API = BASE_URL + "/api/coordination/allocation-plan";
    private static final String DECISION_API = BASE_URL + "/api/coordination/decision";
    private static final String RELIEF_REQUESTS_API = BASE_URL + "/api/relief-requests";

    @Override
    public void start(Stage stage) {
        root = new BorderPane();
        root.setStyle("-fx-background-color: " + background + ";");

        sidebar = new VBox(12);
        sidebar.setPadding(new Insets(28, 18, 24, 18));
        sidebar.setPrefWidth(245);
        sidebar.setStyle("-fx-background-color: " + navy + ";");

        Label logo = new Label("ReliefMesh");
        logo.setStyle("-fx-font-size: 27px; -fx-font-weight: bold; -fx-text-fill: white;");
        Label tagline = new Label("RELIEF OPERATIONS");
        tagline.setStyle("-fx-text-fill: #A8B8CC; -fx-font-size: 10px; -fx-font-weight: bold;");
        sidebar.getChildren().addAll(logo, tagline, new Label(" "));

        addNav("Overview", "Overview");
        addNav("Relief Management", "Relief Management");
        addNav("Delivery Monitoring", "Delivery Monitoring");
        addNav("Adaptive Coordination", "Adaptive Coordination");
        addNav("Live Satellite Map", "Live Satellite Map");
        addNav("Decision Review", "Decision Review");
        addNav("Live Relief Map", "Live Satellite Map");

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        Label footer = new Label("Concurrent Humanitarian\nRelief Coordination");
        footer.setStyle("-fx-text-fill: #A8B8CC; -fx-font-size: 11px;");
        sidebar.getChildren().addAll(spacer, footer);
        root.setLeft(sidebar);

        showOverview();
        Scene scene = new Scene(root, 1250, 780);
        stage.setTitle("ReliefMesh | Operations Centre");
        stage.setMinWidth(950);
        stage.setMinHeight(650);
        stage.setScene(scene);
        stage.show();
    }

    private void addNav(String text, String pageName) {
        Button button = new Button(text);
        button.setMaxWidth(Double.MAX_VALUE);
        button.setAlignment(Pos.CENTER_LEFT);
        button.setPadding(new Insets(13, 12, 13, 14));
        button.setStyle(navStyle(false));
        button.setOnAction(event -> {
            if (activeButton != null) activeButton.setStyle(navStyle(false));
            activeButton = button;
            activeButton.setStyle(navStyle(true));
            switch (pageName) {
                case "Overview": showOverview(); break;
                case "Relief Management": showRelief(); break;
                case "Delivery Monitoring": showDeliveries(); break;
                case "Adaptive Coordination": showCoordination(); break;
                case "Decision Review": showDecisionReview(); break;
                case "Live Satellite Map":
                    showLiveMap();
                    break;
                default: break;
            }
        });
        sidebar.getChildren().add(button);
        if (pageName.equals("Overview")) {
            activeButton = button;
            button.setStyle(navStyle(true));
        }
    }
    


    private void showLiveMap() {
        preparePage(
            "Live Relief Map",
            "Live requests, warehouses, vehicles, and delivery routes."
        );

        LiveMapView mapView = new LiveMapView();
        mapView.setPrefHeight(700);
        mapView.setMinHeight(600);

        content.getChildren().add(mapView);
    }


    private String navStyle(boolean selected) {
        if (selected) return "-fx-background-color: #2563EB; -fx-text-fill: white; -fx-font-size: 13px; -fx-background-radius: 8; -fx-cursor: hand;";
        return "-fx-background-color: transparent; -fx-text-fill: #D7E0EC; -fx-font-size: 13px; -fx-background-radius: 8; -fx-cursor: hand;";
    }

    private void preparePage(String title, String description) {
        VBox page = new VBox(20);
        page.setPadding(new Insets(30));
        page.setStyle("-fx-background-color: " + background + ";");
        Label heading = new Label(title);
        heading.setStyle("-fx-font-size: 28px; -fx-font-weight: bold; -fx-text-fill: #14243A;");
        Label subtitle = new Label(description);
        subtitle.setWrapText(true);
        subtitle.setStyle("-fx-font-size: 13px; -fx-text-fill: #64748B;");
        page.getChildren().addAll(heading, subtitle);
        ScrollPane scroll = new ScrollPane(page);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background: " + background + "; -fx-background-color: " + background + ";");
        root.setCenter(scroll);
        content = page;
    }

    private VBox card(String title, String value, String note) {
        VBox box = new VBox(10);
        box.setPadding(new Insets(20));
        box.setPrefWidth(200);
        box.setMinHeight(125);
        box.setStyle("-fx-background-color: white; -fx-background-radius: 12; -fx-border-color: #E2E8F0; -fx-border-radius: 12;");
        Label name = new Label(title);
        name.setStyle("-fx-text-fill: #64748B; -fx-font-size: 12px;");
        Label number = new Label(value);
        number.setStyle("-fx-text-fill: #14243A; -fx-font-size: 28px; -fx-font-weight: bold;");
        Label detail = new Label(note);
        detail.setWrapText(true);
        detail.setStyle("-fx-text-fill: #64748B; -fx-font-size: 11px;");
        box.getChildren().addAll(name, number, detail);
        return box;
    }

    private HBox cardRow(VBox... cards) {
        HBox row = new HBox(15);
        row.getChildren().addAll(cards);
        for (VBox item : cards) HBox.setHgrow(item, Priority.ALWAYS);
        return row;
    }

    private Label section(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-size: 17px; -fx-font-weight: bold; -fx-text-fill: #14243A;");
        return label;
    }

    private Label info(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setStyle("-fx-font-size: 13px; -fx-text-fill: #475569;");
        return label;
    }

    private Button primaryButton(String text) {
        Button button = new Button(text);
        button.setStyle("-fx-background-color: " + blue + "; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 7; -fx-padding: 10 16 10 16;");
        return button;
    }

    private VBox whitePanel() {
        VBox panel = new VBox(15);
        panel.setPadding(new Insets(22));
        panel.setStyle("-fx-background-color: white; -fx-background-radius: 12; -fx-border-color: #E2E8F0; -fx-border-radius: 12;");
        return panel;
    }

    private void showOverview() {
        preparePage("Operations Overview", "Monitor relief needs, resources, and ongoing deliveries.");
        VBox pendingCard = card("Pending Requests", "—", "Requests awaiting allocation");
        VBox foodCard = card("Food Kits Available", "—", "Current food inventory");
        VBox medicalCard = card("Medical Kits", "—", "Current medical inventory");
        VBox vehiclesCard = card("Available Vehicles", "—", "Vehicles currently available");
        pendingValue = (Label) pendingCard.getChildren().get(1);
        foodValue = (Label) foodCard.getChildren().get(1);
        medicalValue = (Label) medicalCard.getChildren().get(1);
        vehiclesValue = (Label) vehiclesCard.getChildren().get(1);

        Button refresh = primaryButton("Refresh Data");
        refresh.setOnAction(event -> loadDashboardData());
        connectionStatus = info("Connecting to ReliefMesh backend...");

        VBox alert = whitePanel();
        alert.getChildren().addAll(section("Coordination Alert"),
                info("Review unmet demand, unreserved inventory, vehicle capacity, and existing commitments before proposing a new delivery."));

        VBox quick = whitePanel();
        quick.getChildren().add(section("Quick Navigation"));
        Button requests = primaryButton("Open Relief Management");
        requests.setOnAction(event -> showRelief());
        Button deliveries = primaryButton("Open Delivery Monitoring");
        deliveries.setOnAction(event -> showDeliveries());
        Button coordination = primaryButton("View Live Coordination");
        coordination.setOnAction(event -> showCoordination());
        Button decisions = primaryButton("Review Allocation Plan");
        decisions.setOnAction(event -> showDecisionReview());
        quick.getChildren().addAll(requests, deliveries, coordination, decisions);

        content.getChildren().addAll(cardRow(pendingCard, foodCard, medicalCard, vehiclesCard),
                refresh, connectionStatus, alert, quick);
        loadDashboardData();
    }

    private void loadDashboardData() {
        final Label status = connectionStatus;
        if (status == null) return;
        status.setText("Refreshing live data...");
        sendGet(DASHBOARD_API, (response, error) -> {
            if (connectionStatus != status) return;
            if (error != null) {
                status.setText("Backend unavailable. Start Tomcat and refresh.");
                System.err.println("Dashboard API error: " + error);
                return;
            }
            if (response.statusCode() != 200) {
                status.setText("Dashboard API returned HTTP " + response.statusCode());
                return;
            }
            try {
                String json = response.body();
                pendingValue.setText(String.valueOf(getNumber(json, "pendingRequests")));
                foodValue.setText(String.valueOf(getNumber(json, "foodKits")));
                medicalValue.setText(String.valueOf(getNumber(json, "medicalKits")));
                vehiclesValue.setText(String.valueOf(getNumber(json, "availableVehicles")));
                status.setText("Connected | Overview loaded from backend.");
            } catch (RuntimeException ex) {
                status.setText("Could not read dashboard data.");
                System.err.println("Dashboard JSON error: " + ex);
            }
        });
    }


private void showRelief() {
    preparePage("Relief Management",
            "Register emergencies and monitor live relief requests.");

    // Live request table
    reliefRequestsTable = new TableView<>();
    reliefRequestsTable.setColumnResizePolicy(
            TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    reliefRequestsTable.setPrefHeight(330);
    reliefRequestsTable.setMinHeight(230);
    reliefRequestsTable.setPlaceholder(
            new Label("No relief requests found."));

    addReliefRequestColumn("Request ID", 0);
    addReliefRequestColumn("Requester", 1);
    addReliefRequestColumn("Location", 2);
    addReliefRequestColumn("Emergency", 3);
    addReliefRequestColumn("Severity", 4);
    addReliefRequestColumn("People Affected", 5);
    addReliefRequestColumn("Status", 6);

    // Highlight critical requests
    reliefRequestsTable.setRowFactory(table -> new TableRow<>() {
        @Override
        protected void updateItem(String[] row, boolean empty) {
            super.updateItem(row, empty);

            if (empty || row == null) {
                setStyle("");
            } else if ("5".equals(row[4])) {
                setStyle("-fx-background-color: #FEE2E2;");
            } else {
                setStyle("");
            }
        }
    });

    Button refreshRequests = primaryButton("Refresh Requests");
    refreshRequests.setOnAction(event -> loadReliefRequests());

    reliefRequestsStatus = info("Loading relief requests...");

    VBox recordsPanel = whitePanel();
    recordsPanel.getChildren().addAll(
            section("Registered Relief Requests"),
            info("Requests are loaded from MySQL through the Servlet API. "
                    + "Severity 5 requests are highlighted."),
            refreshRequests,
            reliefRequestsStatus,
            reliefRequestsTable
    );

    // New request form
    TextField person = new TextField();
    person.setPromptText("Requester name");

    TextField location = new TextField();
    location.setPromptText("Community / location");

    ComboBox<String> emergency = new ComboBox<>();
    emergency.getItems().addAll(
            "Flood", "Fire", "Medical Emergency", "Earthquake", "Other");
    emergency.setPromptText("Emergency type");
    emergency.setMaxWidth(Double.MAX_VALUE);

    Spinner<Integer> severity = new Spinner<>(1, 5, 3);
    severity.setEditable(true);
    severity.setMaxWidth(Double.MAX_VALUE);

    TextField people = new TextField();
    people.setPromptText("Number of people affected");

    ComboBox<String> resource = new ComboBox<>();
    resource.getItems().addAll("Food Kits", "Medical Kits", "Blankets");
    resource.setValue("Food Kits");
    resource.setMaxWidth(Double.MAX_VALUE);

    TextField quantity = new TextField();
    quantity.setPromptText("Required quantity");

    VBox form = whitePanel();
    form.setMaxWidth(650);
    form.getChildren().addAll(
            section("Register a Relief Request"),
            info("Severity: 1 = Low, 5 = Critical."),
            person,
            location,
            emergency,
            new Label("Severity (1–5)"),
            severity,
            people,
            resource,
            quantity
    );

    Button submit = primaryButton("Submit Relief Request");
    Label message = info("Complete all fields to register a request.");

    submit.setOnAction(event -> {
        String requester = person.getText().trim();
        String place = location.getText().trim();
        String type = emergency.getValue();
        String resourceName = resource.getValue();

        if (requester.isEmpty() || place.isEmpty() || type == null
                || resourceName == null
                || people.getText().trim().isEmpty()
                || quantity.getText().trim().isEmpty()) {
            message.setText("Please complete all fields.");
            return;
        }

        int peopleCount;
        int quantityValue;

        try {
            peopleCount = Integer.parseInt(people.getText().trim());
            quantityValue = Integer.parseInt(quantity.getText().trim());
        } catch (NumberFormatException ex) {
            message.setText(
                    "People affected and quantity must be whole numbers.");
            return;
        }

        int severityValue = severity.getValue();

        if (peopleCount < 1 || quantityValue < 1) {
            message.setText(
                    "People affected and quantity must be greater than zero.");
            return;
        }

        if (peopleCount > 10_000_000 || quantityValue > 1_000_000) {
            message.setText("The entered value exceeds the allowed limit.");
            return;
        }

        String body = "requesterName=" + encodeForm(requester)
                + "&location=" + encodeForm(place)
                + "&emergencyType=" + encodeForm(type)
                + "&severity=" + severityValue
                + "&peopleAffected=" + peopleCount
                + "&resourceName=" + encodeForm(resourceName)
                + "&requiredQuantity=" + quantityValue;

        submit.setDisable(true);
        message.setText("Saving request to MySQL...");

        postForm(BASE_URL + "/relief-request", body, (response, error) -> {
            submit.setDisable(false);

            if (error != null) {
                message.setText(
                        "Connection failed. Check that Tomcat is running.");
                return;
            }

            if (response.statusCode() == 201) {
                message.setText(
                        "Request registered successfully. "
                        + "The request and resource requirement were saved.");

                person.clear();
                location.clear();
                emergency.setValue(null);
                severity.getValueFactory().setValue(3);
                people.clear();
                resource.setValue("Food Kits");
                quantity.clear();

                loadReliefRequests();
                loadDashboardDataIfVisible();
            } else {
                message.setText("Request not saved (HTTP "
                        + response.statusCode()
                        + "). Check the resource and server logs.");

                System.err.println(
                        "Relief request response: " + response.body());
            }
        });
    });

    form.getChildren().addAll(submit, message);

    content.getChildren().addAll(recordsPanel, form);

    loadReliefRequests();
}

private void addReliefRequestColumn(String heading, int index) {
    TableColumn<String[], String> column = new TableColumn<>(heading);
    column.setCellValueFactory(
            data -> new SimpleStringProperty(data.getValue()[index]));
    reliefRequestsTable.getColumns().add(column);
}

private void loadReliefRequests() {
    final TableView<String[]> table = reliefRequestsTable;
    final Label status = reliefRequestsStatus;

    if (table == null || status == null) {
        return;
    }

    status.setText("Loading live relief requests...");

    sendGet(RELIEF_REQUESTS_API, (response, error) -> {
        // Ignore responses belonging to a page that is no longer active.
        if (reliefRequestsTable != table
                || reliefRequestsStatus != status) {
            return;
        }

        if (error != null) {
            status.setText(
                    "Could not connect to the requests API. Check Tomcat.");
            System.err.println("Relief requests API error: " + error);
            return;
        }

        if (response.statusCode() != 200) {
            status.setText("Requests API returned HTTP "
                    + response.statusCode());
            System.err.println(
                    "Relief requests response: " + response.body());
            return;
        }

        try {
            String json = response.body();

            if (json == null || !json.trim().startsWith("[")) {
                throw new IllegalArgumentException(
                        "Expected a JSON array.");
            }

            ObservableList<String[]> rows =
                    FXCollections.observableArrayList();

            Matcher matcher =
                    Pattern.compile("\\{([^{}]*)\\}").matcher(json);

            while (matcher.find()) {
                String object = matcher.group();

                if (!object.contains("\"requestId\"")) {
                    continue;
                }

                rows.add(new String[] {
                    getNumberString(object, "requestId"),
                    getJsonString(object, "requesterName"),
                    getJsonString(object, "location"),
                    getJsonString(object, "emergencyType"),
                    getNumberString(object, "severity"),
                    getNumberString(object, "peopleAffected"),
                    getJsonString(object, "status")
                });
            }

            table.setItems(rows);
            status.setText("Connected | " + rows.size()
                    + " relief request(s) loaded from MySQL.");

        } catch (RuntimeException ex) {
            status.setText(
                    "Could not read requests. Check the API JSON.");
            System.err.println("Relief requests JSON error: " + ex);
        }
    });
}

    private void showDeliveries() {
        preparePage("Delivery Monitoring", "View delivery records and manage validated delivery operations.");
        deliveryTable = new TableView<>();
        deliveryTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        deliveryTable.setPlaceholder(new Label("Loading deliveries or no records found..."));
        deliveryTable.setPrefHeight(350); deliveryTable.setMinHeight(250);
        addDeliveryColumn("Delivery ID", 0); addDeliveryColumn("Request ID", 1);
        addDeliveryColumn("Resource", 2); addDeliveryColumn("Quantity", 3);
        addDeliveryColumn("Status", 4); addDeliveryColumn("Created At", 5);
        deliveryStatus = info("Loading delivery records...");
        Button refresh = primaryButton("Refresh Deliveries"); refresh.setOnAction(event -> loadDeliveries());
        VBox recordsPanel = whitePanel();
        recordsPanel.getChildren().addAll(section("Live Delivery Records"), refresh, deliveryStatus, deliveryTable);

        TextField requestId = new TextField(); requestId.setPromptText("Relief request ID");
        TextField vehicleId = new TextField(); vehicleId.setPromptText("Available vehicle ID");
        ComboBox<String> resource = new ComboBox<>();
        resource.getItems().addAll("Food Kits", "Medical Kits", "Blankets");
        resource.setValue("Food Kits"); resource.setMaxWidth(Double.MAX_VALUE);
        TextField quantity = new TextField(); quantity.setPromptText("Quantity to deliver");
        Label assignmentMessage = info("Enter a valid request, available vehicle, resource and quantity.");
        Button assign = primaryButton("Assign Delivery");
        assign.setOnAction(event -> {
            int req, vehicle, qty;
            try {
                req = Integer.parseInt(requestId.getText().trim());
                vehicle = Integer.parseInt(vehicleId.getText().trim());
                qty = Integer.parseInt(quantity.getText().trim());
            } catch (NumberFormatException ex) {
                assignmentMessage.setText("Enter valid numeric request, vehicle and quantity IDs."); return;
            }
            if (req <= 0 || vehicle <= 0 || qty <= 0) {
                assignmentMessage.setText("IDs and quantity must be greater than zero."); return;
            }
            String body = "requestId=" + encodeForm(String.valueOf(req))
                    + "&vehicleId=" + encodeForm(String.valueOf(vehicle))
                    + "&resourceName=" + encodeForm(resource.getValue())
                    + "&quantity=" + encodeForm(String.valueOf(qty));
            assign.setDisable(true); assignmentMessage.setText("Validating stock, request and vehicle...");
            postForm(BASE_URL + "/deliveries", body, (response, error) -> {
                assign.setDisable(false);
                if (error != null) { assignmentMessage.setText("Connection failed. Check Tomcat."); return; }
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    assignmentMessage.setText("Assignment request accepted. Refresh and verify the record.");
                    loadDeliveries(); loadDashboardDataIfVisible();}
                    else {
                        String errorBody = response.body() == null
                                ? "No error details returned by server."
                                : response.body();

                        assignmentMessage.setText(
                                "Assignment failed (HTTP "
                                        + response.statusCode()
                                        + "). See details below."
                        );

                        System.err.println("Delivery assignment response: " + errorBody);
                    }
            });
        });
        VBox assignmentPanel = whitePanel();
        assignmentPanel.getChildren().addAll(section("Create Delivery Assignment"),
                info("The backend validates inventory, vehicle availability, capacity and request existence before creating a delivery."),
                requestId, vehicleId, resource, quantity, assign, assignmentMessage);

        TextField deliveryId = new TextField(); deliveryId.setPromptText("Delivery ID");
        ComboBox<String> newStatus = new ComboBox<>();
        newStatus.getItems().addAll("IN_TRANSIT", "DELIVERED", "CANCELLED");
        newStatus.setPromptText("Select new status"); newStatus.setMaxWidth(Double.MAX_VALUE);
        Label updateMessage = info("Select a delivery ID and the status to apply.");
        Button update = primaryButton("Update Delivery Status");
        update.setOnAction(event -> {
            int id;
            try { id = Integer.parseInt(deliveryId.getText().trim()); }
            catch (NumberFormatException ex) { updateMessage.setText("Enter a valid delivery ID."); return; }
            if (id <= 0 || newStatus.getValue() == null) {
                updateMessage.setText("Enter a positive delivery ID and select a status."); return;
            }
            String body = "deliveryId=" + encodeForm(String.valueOf(id))
                    + "&status=" + encodeForm(newStatus.getValue());
            update.setDisable(true); updateMessage.setText("Updating delivery status...");
            postForm(BASE_URL + "/delivery-status", body, (response, error) -> {
                update.setDisable(false);
                if (error != null) { updateMessage.setText("Connection failed. Check Tomcat."); return; }
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    updateMessage.setText("Status request accepted. Refresh to verify the final status.");
                    loadDeliveries(); loadDashboardDataIfVisible();
                } else {
                    updateMessage.setText("Status update failed (HTTP " + response.statusCode() + ").");
                    System.err.println("Delivery status response: " + response.body());
                }
            });
        });
        VBox statusPanel = whitePanel();
        statusPanel.getChildren().addAll(section("Update Delivery Status"),
                info("Allowed transitions are validated by the backend. DELIVERED deducts stock; terminal deliveries cannot be processed again."),
                deliveryId, newStatus, update, updateMessage);
        content.getChildren().addAll(recordsPanel, assignmentPanel, statusPanel);
        loadDeliveries();
    }

    private void addDeliveryColumn(String heading, int index) {
        TableColumn<String[], String> column = new TableColumn<>(heading);
        column.setCellValueFactory(data -> new SimpleStringProperty(data.getValue()[index]));
        deliveryTable.getColumns().add(column);
    }

    private void loadDeliveries() {
        final Label status = deliveryStatus;
        final TableView<String[]> table = deliveryTable;
        if (status == null || table == null) return;
        status.setText("Loading live delivery records...");
        sendGet(DELIVERIES_API, (response, error) -> {
            if (deliveryTable != table || deliveryStatus != status) return;
            if (error != null) { status.setText("Could not connect to the delivery API. Check Tomcat and the endpoint."); System.err.println("Delivery API error: " + error); return; }
            if (response.statusCode() != 200) { status.setText("Delivery API returned HTTP " + response.statusCode()); System.err.println("Delivery response: " + response.body()); return; }
            try {
                String json = response.body();
                if (json == null || !json.trim().startsWith("[")) throw new IllegalArgumentException("Expected a JSON array.");
                ObservableList<String[]> rows = FXCollections.observableArrayList();
                Matcher matcher = Pattern.compile("\\{([^{}]*)\\}").matcher(json);
                while (matcher.find()) {
                    String object = matcher.group();
                    if (!object.contains("\"deliveryId\"")) continue;
                    rows.add(new String[] { getNumberString(object, "deliveryId"), getNumberString(object, "requestId"),
                            getJsonString(object, "resourceName"), getNumberString(object, "quantity"),
                            getJsonString(object, "status"), getOptionalJsonString(object, "createdAt") });
                }
                table.setItems(rows);
                status.setText("Connected | " + rows.size() + " delivery record(s) loaded.");
            } catch (RuntimeException ex) {
                status.setText("Could not read delivery data. Check the API JSON.");
                System.err.println("Delivery JSON error: " + ex);
            }
        });
    }


private void showCoordination() {
    preparePage(
        "Adaptive Coordination",
        "Evaluate pending needs against inventory and active reservations."
    );

    coordinationVehicles = info("Loading vehicle information...");
    coordinationStatus = info("Connecting to coordination API...");

    Button refresh = primaryButton("Refresh Coordination");
    refresh.setOnAction(event -> {
        loadCoordination();
        loadCoordinationPreview();
    });

    coordinationTable = new TableView<>();
    coordinationTable.setColumnResizePolicy(
        TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN
    );
    coordinationTable.setPrefHeight(320);
    coordinationTable.setMinHeight(250);
    coordinationTable.setFixedCellSize(-1);
    coordinationTable.setPlaceholder(
        new Label("No resource analysis available.")
    );

    addCoordinationColumn("Resource", 0);
    addCoordinationColumn("Demand", 1);
    addCoordinationColumn("Physical Stock", 2);
    addCoordinationColumn("Reserved", 3);
    addCoordinationColumn("Unreserved", 4);
    addCoordinationColumn("Severity", 5);
    addCoordinationColumn("Shortage", 6);
    addCoordinationColumn("Feasible*", 7);
    addCoordinationColumn("Recommendation", 8);

    allocationPreviewStatus = info("Loading request-level proposals...");

    allocationPreviewTable = new TableView<>();
    allocationPreviewTable.setColumnResizePolicy(
        TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN
    );
    allocationPreviewTable.setPrefHeight(340);
    allocationPreviewTable.setMinHeight(270);
    allocationPreviewTable.setFixedCellSize(-1);
    allocationPreviewTable.setPlaceholder(
        new Label("No allocation proposals available.")
    );

    addPreviewColumn("Request ID", 0);
    addPreviewColumn("Requester", 1);
    addPreviewColumn("Resource", 2);
    addPreviewColumn("Unmet Need", 3);
    addPreviewColumn("Proposed Qty", 4);
    addPreviewColumn("Outcome", 5);
    addPreviewColumn("Explanation", 6);

    VBox panel = whitePanel();

    panel.getChildren().addAll(
        section("Live Resource Analysis"),
        info(
            "Resource totals summarize pending demand and available "
            + "inventory. Feasibility is an estimate, not an approved allocation."
        ),
        coordinationVehicles,
        refresh,
        coordinationStatus,
        coordinationTable,
        section("Request-Level Allocation Proposals"),
        info(
            "Proposals share the same unreserved stock pool. They are "
            + "advisory and do not reserve inventory or create deliveries."
        ),
        allocationPreviewStatus,
        allocationPreviewTable
    );

    content.getChildren().add(panel);

    loadCoordination();
    loadCoordinationPreview();
}



private void addPreviewColumn(String heading, int index) {
    TableColumn<String[], String> column = new TableColumn<>(heading);

    column.setCellValueFactory(
        data -> new SimpleStringProperty(data.getValue()[index])
    );

    switch (index) {
        case 0:
            column.setMinWidth(85);
            column.setPrefWidth(95);
            break;
        case 1:
            column.setMinWidth(105);
            column.setPrefWidth(125);
            break;
        case 2:
            column.setMinWidth(105);
            column.setPrefWidth(125);
            break;
        case 3:
        case 4:
            column.setMinWidth(95);
            column.setPrefWidth(105);
            break;
        case 5:
            column.setMinWidth(175);
            column.setPrefWidth(195);
            break;
        case 6:
            column.setMinWidth(280);
            column.setPrefWidth(350);
            break;
        default:
            break;
    }

    if (index == 5 || index == 6) {
        column.setCellFactory(col -> new TableCell<String[], String>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);

                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(item.replace('_', ' '));
                    setWrapText(true);

                    if (index == 5) {
                        setStyle("-fx-font-weight: bold;");
                    } else {
                        setStyle("-fx-text-fill: #475569;");
                    }
                }
            }
        });
    }

    allocationPreviewTable.getColumns().add(column);
}


    private void loadCoordinationPreview() {
        final Label status = allocationPreviewStatus;
        final TableView<String[]> table = allocationPreviewTable;
        if (status == null || table == null) return;
        status.setText("Loading shared-stock allocation proposals...");
        sendGet(ALLOCATION_PLAN_API, (response, error) -> {
            if (allocationPreviewStatus != status || allocationPreviewTable != table) return;
            if (error != null) { status.setText("Could not connect to the allocation-plan API."); return; }
            if (response.statusCode() != 200) {
                status.setText("Allocation-plan API returned HTTP " + response.statusCode());
                System.err.println("Allocation plan: " + response.body()); return;
            }
            try {
                ObservableList<String[]> rows = FXCollections.observableArrayList();
                Matcher matcher = Pattern.compile("\\{([^{}]*)\\}").matcher(response.body());
                while (matcher.find()) {
                    String object = matcher.group();
                    if (!object.contains("\"outcome\"")) continue;
                    rows.add(new String[] { getOptionalNumberString(object, "requestId"),
                            getOptionalJsonString(object, "requesterName"), getOptionalJsonString(object, "resourceName"),
                            getOptionalNumberString(object, "outstandingQuantity"), getOptionalNumberString(object, "proposedQuantity"),
                            getOptionalJsonString(object, "outcome"), getOptionalJsonString(object, "explanation") });
                }
                table.setItems(rows);
                status.setText("Connected | " + rows.size() + " request-level proposal(s) loaded. Advisory only.");
            } catch (RuntimeException ex) { status.setText("Could not parse allocation-plan response."); System.err.println("Allocation preview error: " + ex); }
        });
    }


private void addCoordinationColumn(String heading, int index) {
    TableColumn<String[], String> column = new TableColumn<>(heading);

    column.setCellValueFactory(
        data -> new SimpleStringProperty(data.getValue()[index])
    );

    if (index == 0) {
        column.setMinWidth(110);
    } else if (index == 8) {
        column.setMinWidth(230);
        column.setPrefWidth(300);

        column.setCellFactory(col -> new TableCell<String[], String>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);

                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                } else {
                    String text = item.replace('_', ' ');
                    setText(text);
                    setWrapText(true);

                    String lower = text.toLowerCase();

                    if (lower.contains("shortage")
                            || lower.contains("no unreserved stock")
                            || lower.contains("insufficient")) {
                        setStyle(
                            "-fx-text-fill: #B91C1C;"
                            + "-fx-font-weight: bold;"
                        );
                    } else {
                        setStyle("-fx-text-fill: #166534;");
                    }
                }
            }
        });
    }

    coordinationTable.getColumns().add(column);
}


    private void loadCoordination() {
        final Label status = coordinationStatus;
        final Label vehicles = coordinationVehicles;
        final TableView<String[]> table = coordinationTable;
        if (status == null || vehicles == null || table == null) return;
        status.setText("Refreshing live coordination data...");
        sendGet(COORDINATION_API, (response, error) -> {
            if (coordinationTable != table || coordinationStatus != status) return;
            if (error != null) { status.setText("Could not connect. Check Tomcat and /api/coordination."); System.err.println("Coordination API error: " + error); return; }
            if (response.statusCode() != 200) { status.setText("Coordination API returned HTTP " + response.statusCode()); System.err.println("Coordination response: " + response.body()); return; }
            try {
                String json = response.body();
                if (json == null || json.contains("\"error\"")) throw new IllegalArgumentException("The server returned an API error.");
                int availableVehicles = getNumber(json, "availableVehicles");
                int largestCapacity = getNumberOrDefault(json, "largestAvailableVehicleCapacity",
                        getNumberOrDefault(json, "largestVehicleCapacity", 0));
                ObservableList<String[]> rows = FXCollections.observableArrayList();
                Matcher matcher = Pattern.compile("\\{([^{}]*)\\}").matcher(json);
                while (matcher.find()) {
                    String object = matcher.group();
                    if (!object.contains("\"resourceName\"")) continue;
                    rows.add(new String[] { getJsonString(object, "resourceName"), getNumberString(object, "pendingDemand"),
                            getNumberString(object, "physicalStock"), getNumberString(object, "reservedQuantity"),
                            getNumberString(object, "unreservedStock"), getNumberString(object, "highestSeverity"),
                            getNumberString(object, "shortage"), getNumberString(object, "feasibleQuantity"),
                            getJsonString(object, "recommendation") });
                }
                table.setItems(rows);
                vehicles.setText("Available vehicles: " + availableVehicles + " | Largest available capacity: " + largestCapacity);
                status.setText("Connected | " + rows.size() + " live resource recommendation(s) loaded.");
            } catch (RuntimeException ex) { status.setText("Could not read coordination data. Check API JSON."); System.err.println("Coordination JSON error: " + ex); }
        });
    }

    private void showDecisionReview() {
        preparePage("Decision Review", "Review proposed allocations before taking operational action.");
        decisionStatus = info("Loading allocation plan...");
        decisionTable = new TableView<>();
        decisionTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        decisionTable.setPrefHeight(430); decisionTable.setMinHeight(280);
        decisionTable.setPlaceholder(new Label("Loading proposed allocations..."));
        addDecisionColumn("Request ID", 0); addDecisionColumn("Requester", 1);
        addDecisionColumn("Resource", 2); addDecisionColumn("Proposed Qty", 3);
        addDecisionColumn("Outcome", 4); addDecisionColumn("Explanation", 5);
        addDecisionColumn("Vehicle ID", 6);
        addDecisionColumn("Source Resource ID", 7);

        Button refresh = primaryButton("Refresh Allocation Plan");
        refresh.setOnAction(event -> loadAllocationPlan());
        TextField decisionReason = new TextField();
        decisionReason.setPromptText("Reason for approval or rejection (required)");
        Button approve = primaryButton("Approve Proposal");
        Button reject = primaryButton("Reject Proposal");
        approve.setDisable(true); reject.setDisable(true);
        Label decisionMessage = info("Select a proposal from the table to review it.");

        decisionTable.getSelectionModel().selectedItemProperty().addListener((observable, oldRow, newRow) -> {
            boolean eligible = newRow != null && isPositiveQuantity(newRow[3])
                    && ("PROPOSAL".equals(newRow[4]) || "PARTIAL_PROPOSAL".equals(newRow[4]));
            approve.setDisable(!eligible);
            reject.setDisable(newRow == null);
            if (newRow == null) decisionMessage.setText("Select a proposal from the table.");
            else decisionMessage.setText("Selected request #" + newRow[0] + " | " + newRow[2] + " | Proposed quantity: " + newRow[3]);
        });

        approve.setOnAction(event -> submitAllocationDecision("APPROVE", decisionReason, decisionMessage, approve, reject));
        reject.setOnAction(event -> submitAllocationDecision("REJECT", decisionReason, decisionMessage, approve, reject));

        VBox panel = whitePanel();
        panel.getChildren().addAll(section("Proposed Allocation Plan"),
                info("The plan evaluates potential allocations using unmet demand, available stock, vehicle availability, and existing commitments."),
                info("Proposals remain advisory until a human decision is submitted and validated by the backend."),
                refresh, decisionStatus, decisionTable, section("Human Decision"),
                decisionReason, new HBox(10, approve, reject), decisionMessage);
        content.getChildren().add(panel);
        loadAllocationPlan();
    }

    private void addDecisionColumn(String heading, int index) {
        TableColumn<String[], String> column = new TableColumn<>(heading);
        column.setCellValueFactory(data -> new SimpleStringProperty(data.getValue()[index]));
        decisionTable.getColumns().add(column);
    }

    private boolean isPositiveQuantity(String value) {
        try { return Integer.parseInt(value) > 0; }
        catch (RuntimeException ex) { return false; }
    }


private void submitAllocationDecision(
        String decision,
        TextField reasonField,
        Label message,
        Button approve,
        Button reject) {

    String[] selected = decisionTable.getSelectionModel().getSelectedItem();

    if (selected == null) {
        message.setText("Select a proposal first.");
        return;
    }

    String reason = reasonField.getText().trim();

    if (reason.isEmpty()) {
        message.setText("Enter a reason for your decision.");
        return;
    }

    int requestId;
    int quantity;
    int vehicleId;
    int sourceResourceId;

    try {
        requestId = Integer.parseInt(selected[0]);
        quantity = Integer.parseInt(selected[3]);
        vehicleId = Integer.parseInt(selected[6]);
        sourceResourceId = Integer.parseInt(selected[7]);
    } catch (RuntimeException ex) {
        message.setText(
            "The selected proposal is missing a valid request, quantity, vehicle, or source resource ID. Refresh the allocation plan."
        );
        return;
    }

    if (requestId <= 0 || quantity <= 0
            || vehicleId <= 0 || sourceResourceId <= 0) {
        message.setText("Proposal data contains an invalid ID or quantity.");
        return;
    }

    String action = decision.equalsIgnoreCase("APPROVE")
            ? "approve" : "reject";

    String body =
            "action=" + encodeForm(action)
            + "&requestId=" + encodeForm(String.valueOf(requestId))
            + "&resourceName=" + encodeForm(selected[2])
            + "&quantity=" + encodeForm(String.valueOf(quantity))
            + "&vehicleId=" + encodeForm(String.valueOf(vehicleId))
            + "&warehouseId=" + encodeForm(String.valueOf(sourceResourceId))
            + "&explanation=" + encodeForm(reason)
            + "&decidedBy=" + encodeForm("operator");

    approve.setDisable(true);
    reject.setDisable(true);
    message.setText("Submitting decision for server validation...");

    postForm(DECISION_API, body, (response, error) -> {
        if (error != null) {
            message.setText(
                "Could not contact the server. Check Tomcat and refresh the plan."
            );
            System.err.println("Decision API error: " + error);
            approve.setDisable(false);
            reject.setDisable(false);
            return;
        }

        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            message.setText(
                "Decision accepted by backend: " + response.body()
            );

            reasonField.clear();
            loadAllocationPlan();
            loadDeliveries();
            loadDashboardDataIfVisible();
        } else {
            message.setText(
                "Decision failed (HTTP " + response.statusCode()
                + "). Check the server response."
            );
            System.err.println("Decision API response: " + response.body());

            approve.setDisable(false);
            reject.setDisable(false);
            loadAllocationPlan();
        }
    });
}


    private void loadAllocationPlan() {
        final Label status = decisionStatus;
        final TableView<String[]> table = decisionTable;
        if (status == null || table == null) return;
        status.setText("Loading advisory allocation plan...");
        sendGet(ALLOCATION_PLAN_API, (response, error) -> {
            if (decisionStatus != status || decisionTable != table) return;
            if (error != null) { status.setText("Could not connect to the allocation-plan API. Check Tomcat and the endpoint."); System.err.println("Allocation plan error: " + error); return; }
            if (response.statusCode() != 200) { status.setText("Allocation-plan API returned HTTP " + response.statusCode()); System.err.println("Allocation-plan response: " + response.body()); return; }
            try {
                String json = response.body();
                ObservableList<String[]> rows = FXCollections.observableArrayList();
                Matcher matcher = Pattern.compile("\\{([^{}]*)\\}").matcher(json);
                while (matcher.find()) {
                    String object = matcher.group();
                    if (!object.contains("\"outcome\"")) continue;
                    rows.add(new String[] {
                    	    getOptionalNumberString(object, "requestId"),
                    	    getOptionalJsonString(object, "requesterName"),
                    	    getOptionalJsonString(object, "resourceName"),
                    	    getOptionalNumberString(object, "proposedQuantity"),
                    	    getOptionalJsonString(object, "outcome"),
                    	    getFirstAvailableString(object, "explanation", "reason", "message"),
                    	    getOptionalNumberString(object, "vehicleId"),
                    	    getOptionalNumberString(object, "sourceResourceId")
                    	});
                table.setItems(rows);
                boolean advisoryOnly = json.contains("\"advisoryOnly\":true");
                status.setText("Connected | " + rows.size() + " allocation proposal(s) loaded."
                        + (advisoryOnly ? " Advisory only; no changes were made." : " Review the proposal before acting."));
            }} catch (RuntimeException ex) {
                status.setText("Could not parse the allocation plan JSON.");
                System.err.println("Allocation-plan JSON error: " + ex);
            }
        });
    }

    @FunctionalInterface
    private interface HttpResultHandler { void accept(HttpResponse<String> response, Throwable error); }

    @FunctionalInterface
    private interface PostResultHandler { void accept(HttpResponse<String> response, Throwable error); }

    private void sendGet(String url, HttpResultHandler handler) {
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(8))
                .header("Accept", "application/json").GET().build();
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, error) -> Platform.runLater(() -> handler.accept(response, error)));
    }

    private void postForm(String url, String body, PostResultHandler handler) {
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, error) -> Platform.runLater(() -> handler.accept(response, error)));
    }

    private void loadDashboardDataIfVisible() {
        if (connectionStatus != null) loadDashboardData();
    }

    private String encodeForm(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private int getNumber(String json, String key) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        if (!matcher.find()) throw new IllegalArgumentException("Missing numeric field: " + key);
        return Integer.parseInt(matcher.group(1));
    }

    private int getNumberOrDefault(String json, String key, int defaultValue) {
        try { return getNumber(json, key); } catch (RuntimeException ex) { return defaultValue; }
    }

    private String getNumberString(String json, String key) { return String.valueOf(getNumber(json, key)); }

    private String getOptionalNumberString(String json, String key) {
        try { return getNumberString(json, key); } catch (RuntimeException ex) { return "—"; }
    }

    private String getJsonString(String json, String key) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").matcher(json);
        if (!matcher.find()) throw new IllegalArgumentException("Missing string field: " + key);
        return unescapeJson(matcher.group(1));
    }

    private String getOptionalJsonString(String json, String key) {
        try { return getJsonString(json, key); } catch (RuntimeException ex) { return "—"; }
    }

    private String getFirstAvailableString(String json, String firstKey, String secondKey, String thirdKey) {
        String value = getOptionalJsonString(json, firstKey);
        if (!value.equals("—")) return value;
        value = getOptionalJsonString(json, secondKey);
        if (!value.equals("—")) return value;
        return getOptionalJsonString(json, thirdKey);
    }

    private String unescapeJson(String value) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\') { result.append(c); continue; }
            if (i + 1 >= value.length()) throw new IllegalArgumentException("Invalid JSON escape");
            char next = value.charAt(++i);
            switch (next) {
                case '"': result.append('"'); break;
                case '\\': result.append('\\'); break;
                case '/': result.append('/'); break;
                case 'b': result.append('\b'); break;
                case 'f': result.append('\f'); break;
                case 'n': result.append('\n'); break;
                case 'r': result.append('\r'); break;
                case 't': result.append('\t'); break;
                case 'u':
                    if (i + 4 >= value.length()) throw new IllegalArgumentException("Invalid Unicode escape");
                    result.append((char) Integer.parseInt(value.substring(i + 1, i + 5), 16));
                    i += 4; break;
                default: throw new IllegalArgumentException("Invalid JSON escape: " + next);
            }
        }
        return result.toString();
    }

    @Override
    public void stop() { }

    public static void main(String[] args) { launch(args); }
}
