
package reliefmesh.server;

import reliefmesh.util.Dbconnection;
import reliefmesh.util.RouteDatabaseService;
import reliefmesh.util.RouteOptimizer;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@WebServlet("/api/coordination/allocation-plan")
public class AllocationPlanServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    private static class Need {
        int requestId;
        String requester;
        String location;
        String emergency;
        String resource;
        int severity;
        int required;
        int delivered;
        int committed;

        Need(int requestId, String requester, String location,
             String emergency, String resource, int severity,
             int required, int delivered, int committed) {
            this.requestId = requestId;
            this.requester = requester;
            this.location = location;
            this.emergency = emergency;
            this.resource = resource;
            this.severity = severity;
            this.required = required;
            this.delivered = delivered;
            this.committed = committed;
        }
    }

    private static class StockRow {
        int resourceId;
        String resource;
        String warehouse;
        int physical;
        int reserved;
        int available;

        StockRow(int resourceId, String resource, String warehouse,
                 int physical, int reserved, int available) {
            this.resourceId = resourceId;
            this.resource = resource;
            this.warehouse = warehouse;
            this.physical = physical;
            this.reserved = reserved;
            this.available = available;
        }
    }

    private static class Vehicle {
        int id;
        String name;
        int capacity;
        String location;

        Vehicle(int id, String name, int capacity, String location) {
            this.id = id;
            this.name = name;
            this.capacity = capacity;
            this.location = location;
        }
    }

    private static class RouteChoice {
        StockRow stock;
        Vehicle vehicle;
        int quantity;
        double distance;
        List<Integer> path;

        RouteChoice(StockRow stock, Vehicle vehicle, int quantity,
                    double distance, List<Integer> path) {
            this.stock = stock;
            this.vehicle = vehicle;
            this.quantity = quantity;
            this.distance = distance;
            this.path = path;
        }
    }

    @Override
    protected void doGet(HttpServletRequest request,
                         HttpServletResponse response)
            throws ServletException, IOException {

        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        try (Connection connection = Dbconnection.getConnection()) {

            List<Need> needs = loadNeeds(connection);
            List<StockRow> stock = loadStock(connection);
            List<Vehicle> vehicles = loadVehicles(connection);

            Map<Integer, Integer> legacyReservations =
                    loadLegacyReservations(connection);

            // Protect quantities committed to deliveries that have no
            // recorded source resource row.
            for (StockRow row : stock) {
                int legacy = legacyReservations.getOrDefault(
                        row.resourceId, 0);

                row.reserved += legacy;
                row.available = Math.max(0, row.physical - row.reserved);
            }

            needs.sort(
                Comparator.comparingInt((Need n) -> n.severity).reversed()
                    .thenComparingInt(n -> n.requestId)
            );

            Map<Integer, Integer> planningBalance = new HashMap<>();

            for (StockRow row : stock) {
                planningBalance.put(row.resourceId, row.available);
            }

            Set<Integer> usedVehicles = new HashSet<>();

            StringBuilder json = new StringBuilder();

            json.append("{")
                .append("\"advisoryOnly\":true,")
                .append("\"routeVerification\":\"IMPLEMENTED\",")
                .append("\"availableVehicleCount\":")
                .append(vehicles.size())
                .append(",")
                .append("\"largestAvailableCapacity\":")
                .append(vehicles.stream()
                    .mapToInt(v -> v.capacity)
                    .max()
                    .orElse(0))
                .append(",\"warehouses\":[");

            for (int i = 0; i < stock.size(); i++) {
                StockRow row = stock.get(i);

                if (i > 0) {
                    json.append(",");
                }

                json.append("{")
                    .append("\"resourceId\":").append(row.resourceId)
                    .append(",\"resourceName\":\"")
                    .append(escape(row.resource)).append("\"")
                    .append(",\"warehouse\":\"")
                    .append(escape(row.warehouse)).append("\"")
                    .append(",\"physicalStock\":").append(row.physical)
                    .append(",\"reservedQuantity\":").append(row.reserved)
                    .append(",\"unreservedStock\":").append(row.available)
                    .append("}");
            }

            json.append("],\"items\":[");

            boolean firstItem = true;

            for (Need n : needs) {

                int outstanding = Math.max(
                    0, n.required - n.delivered - n.committed
                );

                if (outstanding <= 0) {
                    continue;
                }

                if (!firstItem) {
                    json.append(",");
                }
                firstItem = false;

                String outcome;
                String explanation;
                String priorityReason;

                boolean routeVerified = false;
                boolean approvalRequired = false;
                RouteChoice selected = null;

                boolean matchingStockExists = false;
                boolean matchingVehicleExists = false;
                boolean routeAttempted = false;

                if (n.severity >= 5) {
                    priorityReason = "Critical emergency.";
                } else if (n.severity >= 4) {
                    priorityReason = "High-priority emergency.";
                } else {
                    priorityReason =
                        "Severity descending, then request ID ascending.";
                }

                // Search stock and vehicles, but accept a proposal only
                // when the warehouse-to-request route can be verified.
                for (StockRow row : stock) {

                    if (!sameText(row.resource, n.resource)) {
                        continue;
                    }

                    int balance = planningBalance.getOrDefault(
                        row.resourceId, 0
                    );

                    if (balance <= 0) {
                        continue;
                    }

                    matchingStockExists = true;

                    for (Vehicle vehicle : vehicles) {

                        if (usedVehicles.contains(vehicle.id)) {
                            continue;
                        }

                        if (vehicle.capacity <= 0) {
                            continue;
                        }

                        matchingVehicleExists = true;

                        int candidate = Math.min(
                            outstanding,
                            Math.min(balance, vehicle.capacity)
                        );

                        if (candidate <= 0) {
                            continue;
                        }

                        routeAttempted = true;

                        try {
                            RouteOptimizer.RouteResult route =
                                RouteDatabaseService.findRoute(
                                    row.warehouse,
                                    n.location
                                );

                            if (route != null
                                    && route.path != null
                                    && !route.path.isEmpty()
                                    && Double.isFinite(route.distance)
                                    && route.distance >= 0) {

                                selected = new RouteChoice(
                                    row,
                                    vehicle,
                                    candidate,
                                    route.distance,
                                    new ArrayList<>(route.path)
                                );

                                break;
                            }

                        } catch (SQLException
                                 | IllegalArgumentException ex) {

                            getServletContext().log(
                                "Route verification failed from '"
                                    + row.warehouse + "' to '"
                                    + n.location + "'.",
                                ex
                            );
                        }
                    }

                    if (selected != null) {
                        break;
                    }
                }

                if (selected == null) {

                    if (!matchingStockExists) {
                        outcome = "NO_UNRESERVED_STOCK";
                        explanation =
                            "No unreserved matching stock is available. "
                            + "Check inventory or replenish stock.";

                        priorityReason =
                            "Search alternatives or replenish stock.";

                    } else if (!matchingVehicleExists) {
                        outcome = "NO_VEHICLE";
                        explanation =
                            "No unused available vehicle with positive "
                            + "capacity was found. Existing commitments "
                            + "remain protected.";

                        priorityReason = "Find an available vehicle.";

                    } else if (routeAttempted) {
                        outcome = "NO_ROUTE_VERIFIED";
                        explanation =
                            "No matching stock-and-vehicle option with a "
                            + "verified route was found. Check the location "
                            + "names and route_nodes/route_edges data. "
                            + "No delivery was created.";

                        priorityReason =
                            "Verify the route or find another feasible source.";

                    } else {
                        outcome = "NO_FEASIBLE_OPTION";
                        explanation =
                            "No feasible stock and vehicle combination "
                            + "was found.";
                    }

                    json.append("{")
                        .append("\"requestId\":").append(n.requestId)
                        .append(",\"requesterName\":\"")
                        .append(escape(n.requester)).append("\"")
                        .append(",\"location\":\"")
                        .append(escape(n.location)).append("\"")
                        .append(",\"emergencyType\":\"")
                        .append(escape(n.emergency)).append("\"")
                        .append(",\"severity\":").append(n.severity)
                        .append(",\"resourceName\":\"")
                        .append(escape(n.resource)).append("\"")
                        .append(",\"requiredQuantity\":").append(n.required)
                        .append(",\"deliveredQuantity\":").append(n.delivered)
                        .append(",\"committedQuantity\":").append(n.committed)
                        .append(",\"outstandingQuantity\":").append(outstanding)
                        .append(",\"unreservedResourceStock\":0")
                        .append(",\"proposedQuantity\":0")
                        .append(",\"sourceResourceId\":-1")
                        .append(",\"recommendedWarehouse\":\"\"")
                        .append(",\"recommendedVehicle\":\"\"")
                        .append(",\"routeVerified\":false")
                        .append(",\"routeDistanceKm\":null")
                        .append(",\"routePath\":[]")
                        .append(",\"overFulfilledOrOverCommitted\":false")
                        .append(",\"commitmentProtected\":true")
                        .append(",\"approvalRequired\":false")
                        .append(",\"outcome\":\"").append(outcome).append("\"")
                        .append(",\"priorityReason\":\"")
                        .append(escape(priorityReason)).append("\"")
                        .append(",\"explanation\":\"")
                        .append(escape(explanation)).append("\"")
                        .append("}");

                    continue;
                }

                routeVerified = true;

                int proposed = selected.quantity;

                // Update only this response's planning balance.
                // Do not change database stock or create deliveries here.
                planningBalance.put(
                    selected.stock.resourceId,
                    planningBalance.get(selected.stock.resourceId) - proposed
                );

                usedVehicles.add(selected.vehicle.id);

                if (proposed < outstanding) {
                    outcome = "PARTIAL_PROPOSAL";
                    explanation =
                        "A partial allocation with a verified route is "
                        + "proposed. The remaining need requires another "
                        + "feasible allocation. No delivery has been created.";
                } else {
                    outcome = "PROPOSAL";
                    explanation =
                        "A stock, vehicle, and route combination was "
                        + "verified. Review the proposal before creating "
                        + "a delivery.";
                }

                json.append("{")
                    .append("\"requestId\":").append(n.requestId)
                    .append(",\"requesterName\":\"")
                    .append(escape(n.requester)).append("\"")
                    .append(",\"location\":\"")
                    .append(escape(n.location)).append("\"")
                    .append(",\"emergencyType\":\"")
                    .append(escape(n.emergency)).append("\"")
                    .append(",\"severity\":").append(n.severity)
                    .append(",\"resourceName\":\"")
                    .append(escape(n.resource)).append("\"")
                    .append(",\"requiredQuantity\":").append(n.required)
                    .append(",\"deliveredQuantity\":").append(n.delivered)
                    .append(",\"committedQuantity\":").append(n.committed)
                    .append(",\"outstandingQuantity\":").append(outstanding)
                    .append(",\"unreservedResourceStock\":")
                    .append(selected.stock.available)
                    .append(",\"proposedQuantity\":").append(proposed)
                    .append(",\"sourceResourceId\":")
                    .append(selected.stock.resourceId)
                    .append(",\"recommendedWarehouse\":\"")
                    .append(escape(selected.stock.warehouse)).append("\"")
                    .append(",\"recommendedVehicle\":\"")
                    .append(escape(selected.vehicle.name)).append("\"")
                    .append(",\"vehicleId\":").append(selected.vehicle.id)
                    .append(",\"routeVerified\":").append(routeVerified)
                    .append(",\"routeDistanceKm\":").append(selected.distance)
                    .append(",\"routePath\":")
                    .append(integerArray(selected.path))
                    .append(",\"overFulfilledOrOverCommitted\":false")
                    .append(",\"commitmentProtected\":true")
                    .append(",\"approvalRequired\":")
                    .append(approvalRequired)
                    .append(",\"outcome\":\"").append(outcome).append("\"")
                    .append(",\"priorityReason\":\"")
                    .append(escape(priorityReason)).append("\"")
                    .append(",\"explanation\":\"")
                    .append(escape(explanation)).append("\"")
                    .append("}");
            }

            json.append("]}");
            response.getWriter().write(json.toString());

        } catch (SQLException e) {

            getServletContext().log(
                "Unable to build the ReliefMesh allocation plan.", e
            );

            response.setStatus(
                HttpServletResponse.SC_INTERNAL_SERVER_ERROR
            );

            response.getWriter().write(
                "{\"error\":\"Unable to build allocation plan. "
                + "Check the server log for details.\"}"
            );
        }
    }

    private List<Need> loadNeeds(Connection connection)
            throws SQLException {

        List<Need> needs = new ArrayList<>();

        String sql =
            "SELECT rr.request_id, rr.requester_name, rr.location, " +
            "rr.emergency_type, rr.severity, cr.resource_name, " +
            "cr.required_quantity AS required_quantity, " +
            "COALESCE(d.delivered, 0) AS delivered, " +
            "COALESCE(d.committed, 0) AS committed " +
            "FROM relief_requests rr " +
            "JOIN community_requirements cr " +
            "ON cr.request_id = rr.request_id " +
            "LEFT JOIN ( " +
            " SELECT request_id, resource_name, " +
            " SUM(CASE WHEN status = 'DELIVERED' " +
            " THEN quantity ELSE 0 END) AS delivered, " +
            " SUM(CASE WHEN status IN ('ASSIGNED', 'IN_TRANSIT') " +
            " THEN quantity ELSE 0 END) AS committed " +
            " FROM deliveries " +
            " GROUP BY request_id, resource_name " +
            ") d ON d.request_id = rr.request_id " +
            "AND LOWER(d.resource_name) = LOWER(cr.resource_name) " +
            "WHERE rr.status IN ('PENDING', 'REVIEW_REQUIRED') " +
            "ORDER BY rr.severity DESC, rr.request_id ASC";

        try (PreparedStatement statement =
                    connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {

            while (result.next()) {
                needs.add(new Need(
                    result.getInt("request_id"),
                    result.getString("requester_name"),
                    result.getString("location"),
                    result.getString("emergency_type"),
                    result.getString("resource_name"),
                    result.getInt("severity"),
                    result.getInt("required_quantity"),
                    result.getInt("delivered"),
                    result.getInt("committed")
                ));
            }
        }

        return needs;
    }

    private List<StockRow> loadStock(Connection connection)
            throws SQLException {

        List<StockRow> stock = new ArrayList<>();

        String sql =
            "SELECT r.resource_id, r.resource_name, " +
            "r.storage_location, r.quantity, " +
            "COALESCE(SUM(CASE " +
            " WHEN d.status IN ('ASSIGNED', 'IN_TRANSIT') " +
            " AND d.source_warehouse_id = r.resource_id " +
            " THEN d.quantity ELSE 0 END), 0) AS reserved " +
            "FROM resources r " +
            "LEFT JOIN deliveries d ON d.source_warehouse_id = r.resource_id " +
            "GROUP BY r.resource_id, r.resource_name, " +
            "r.storage_location, r.quantity " +
            "ORDER BY r.resource_name, r.storage_location";

        try (PreparedStatement statement =
                    connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {

            while (result.next()) {
                int physical = result.getInt("quantity");
                int reserved = result.getInt("reserved");

                stock.add(new StockRow(
                    result.getInt("resource_id"),
                    result.getString("resource_name"),
                    result.getString("storage_location"),
                    physical,
                    reserved,
                    Math.max(0, physical - reserved)
                ));
            }
        }

        return stock;
    }

    private Map<Integer, Integer> loadLegacyReservations(
            Connection connection) throws SQLException {

        Map<Integer, Integer> reservations = new HashMap<>();

        String sql =
            "SELECT r.resource_id, COALESCE(SUM(d.quantity), 0) AS reserved " +
            "FROM resources r " +
            "JOIN deliveries d " +
            "ON LOWER(d.resource_name) = LOWER(r.resource_name) " +
            "WHERE d.status IN ('ASSIGNED', 'IN_TRANSIT') " +
            "AND d.source_warehouse_id IS NULL " +
            "GROUP BY r.resource_id";

        try (PreparedStatement statement =
                    connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {

            while (result.next()) {
                reservations.put(
                    result.getInt("resource_id"),
                    result.getInt("reserved")
                );
            }
        }

        return reservations;
    }

    private List<Vehicle> loadVehicles(Connection connection)
            throws SQLException {

        List<Vehicle> vehicles = new ArrayList<>();

        String sql =
            "SELECT vehicle_id, vehicle_name, capacity, current_location " +
            "FROM vehicles " +
            "WHERE status = 'AVAILABLE' AND capacity > 0 " +
            "ORDER BY capacity DESC, vehicle_id ASC";

        try (PreparedStatement statement =
                    connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {

            while (result.next()) {
                vehicles.add(new Vehicle(
                    result.getInt("vehicle_id"),
                    result.getString("vehicle_name"),
                    result.getInt("capacity"),
                    result.getString("current_location")
                ));
            }
        }

        return vehicles;
    }

    private static boolean sameText(String a, String b) {
        return a != null && b != null
            && a.trim().equalsIgnoreCase(b.trim());
    }

    private static String integerArray(List<Integer> values) {
        StringBuilder result = new StringBuilder("[");

        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                result.append(",");
            }
            result.append(values.get(i));
        }

        result.append("]");
        return result.toString();
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }

        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }
}
