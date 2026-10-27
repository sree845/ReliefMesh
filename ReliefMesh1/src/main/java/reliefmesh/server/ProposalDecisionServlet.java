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
import java.sql.Statement;

@WebServlet("/api/coordination/decision")
public class ProposalDecisionServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    @Override
    protected void doPost(HttpServletRequest request,
                          HttpServletResponse response)
            throws ServletException, IOException {

        response.setContentType("text/plain;charset=UTF-8");

        String action = request.getParameter("action");
        String resourceName = request.getParameter("resourceName");
        String explanation = request.getParameter("explanation");
        String requester = request.getParameter("decidedBy");

        if (action == null ||
            (!action.equalsIgnoreCase("approve")
             && !action.equalsIgnoreCase("reject"))) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.getWriter().println("action must be approve or reject");
            return;
        }

        int requestId;
        int quantity;
        int vehicleId;
        int warehouseId;

        try {
            requestId = Integer.parseInt(request.getParameter("requestId"));
            quantity = Integer.parseInt(request.getParameter("quantity"));
            vehicleId = Integer.parseInt(request.getParameter("vehicleId"));
            warehouseId = Integer.parseInt(request.getParameter("warehouseId"));
        } catch (Exception e) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.getWriter().println(
                "requestId, quantity, vehicleId and warehouseId must be integers");
            return;
        }

        if (requestId <= 0 || quantity <= 0 ||
            vehicleId <= 0 || warehouseId <= 0 ||
            resourceName == null || resourceName.isBlank()) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.getWriter().println("Invalid decision parameters");
            return;
        }

        if (requester == null || requester.isBlank()) {
            requester = "operator";
        }

        try (Connection connection = Dbconnection.getConnection()) {
            connection.setAutoCommit(false);

            try {
                if (action.equalsIgnoreCase("reject")) {
                    recordRejection(connection, requestId, resourceName,
                            quantity, vehicleId, requester, explanation);
                    connection.commit();
                    response.getWriter().println(
                        "Proposal rejected and decision recorded.");
                    return;
                }

                approveProposal(connection, requestId, resourceName,
                        quantity, vehicleId, warehouseId,
                        requester, explanation);

                connection.commit();
                response.getWriter().println(
                    "Proposal approved. Delivery created successfully.");

            } catch (Exception e) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackError) {
                    e.addSuppressed(rollbackError);
                }

                response.setStatus(HttpServletResponse.SC_CONFLICT);
                response.getWriter().println(
                    "Decision was not completed: " + safeMessage(e));

            } finally {
                try {
                    connection.setAutoCommit(true);
                } catch (SQLException ignored) {
                    // Connection is about to close.
                }
            }

        } catch (SQLException e) {
            getServletContext().log("Proposal decision database failure", e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.getWriter().println("Database operation failed.");
        }
    }

    private void approveProposal(
            Connection connection,
            int requestId,
            String resourceName,
            int quantity,
            int vehicleId,
            int warehouseId,
            String decidedBy,
            String explanation) throws Exception {

        // Lock the request so competing approvals cannot allocate it
        // based on the same stale demand calculation.
        int severity;
        String destination;

        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT location, severity " +
                "FROM relief_requests WHERE request_id = ? FOR UPDATE")) {
            ps.setInt(1, requestId);

            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("Relief request does not exist.");
                }
                destination = rs.getString("location");
                severity = rs.getInt("severity");
            }
        }

        // Lock the chosen inventory row.
        int physicalStock;
        String warehouse;

        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT resource_name, storage_location, quantity " +
                "FROM resources WHERE resource_id = ? FOR UPDATE")) {
            ps.setInt(1, warehouseId);

            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("Selected inventory row not found.");
                }

                String actualResource = rs.getString("resource_name");
                if (!actualResource.equalsIgnoreCase(resourceName.trim())) {
                    throw new SQLException(
                        "Selected inventory row does not match the resource.");
                }

                warehouse = rs.getString("storage_location");
                physicalStock = rs.getInt("quantity");
            }
        }

        // Lock the chosen vehicle and ensure it is still available.
        int capacity;

        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT capacity, status FROM vehicles " +
                "WHERE vehicle_id = ? FOR UPDATE")) {
            ps.setInt(1, vehicleId);

            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("Selected vehicle does not exist.");
                }

                capacity = rs.getInt("capacity");
                String status = rs.getString("status");

                if (status == null ||
                    !status.equalsIgnoreCase("AVAILABLE")) {
                    throw new SQLException(
                        "Selected vehicle is no longer available.");
                }
            }
        }

        if (quantity > capacity) {
            throw new SQLException(
                "Proposed quantity exceeds the vehicle capacity.");
        }

        // Recalculate delivered, committed, and outstanding demand.
        int required;
        int delivered;
        int committed;

        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT cr.required_quantity, " +
                "COALESCE((SELECT SUM(d.quantity) FROM deliveries d " +
                " WHERE d.request_id = cr.request_id " +
                " AND LOWER(TRIM(d.resource_name)) = LOWER(TRIM(cr.resource_name)) " +
                " AND UPPER(TRIM(d.status)) = 'DELIVERED'), 0) AS delivered_qty, " +
                "COALESCE((SELECT SUM(d.quantity) FROM deliveries d " +
                " WHERE d.request_id = cr.request_id " +
                " AND LOWER(TRIM(d.resource_name)) = LOWER(TRIM(cr.resource_name)) " +
                " AND UPPER(TRIM(d.status)) IN ('ASSIGNED','IN_TRANSIT')), 0) " +
                "AS committed_qty " +
                "FROM community_requirements cr " +
                "WHERE cr.request_id = ? " +
                "AND LOWER(TRIM(cr.resource_name)) = LOWER(TRIM(?)) " +
                "FOR UPDATE")) {

            ps.setInt(1, requestId);
            ps.setString(2, resourceName);

            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException(
                        "No matching resource requirement exists for this request.");
                }
                required = rs.getInt("required_quantity");
                delivered = rs.getInt("delivered_qty");
                committed = rs.getInt("committed_qty");
            }
        }

        int outstanding = required - delivered - committed;
        if (outstanding <= 0) {
            throw new SQLException(
                "This requirement is already fulfilled or fully committed.");
        }

        if (quantity > outstanding) {
            throw new SQLException(
                "Quantity exceeds outstanding demand of " + outstanding + ".");
        }

        // Subtract active reservations from this exact inventory row.
        int reserved = 0;

        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COALESCE(SUM(quantity), 0) AS reserved_qty " +
                "FROM deliveries WHERE source_warehouse_id = ? " +
                "AND LOWER(TRIM(resource_name)) = LOWER(TRIM(?)) " +
                "AND UPPER(TRIM(status)) IN ('ASSIGNED','IN_TRANSIT')")) {

            ps.setInt(1, warehouseId);
            ps.setString(2, resourceName);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    reserved = rs.getInt("reserved_qty");
                }
            }
        }

        int unreserved = physicalStock - reserved;
        if (unreserved < quantity) {
            throw new SQLException(
                "Insufficient unreserved stock. Available: " +
                Math.max(0, unreserved) + ", requested: " + quantity + ".");
        }

        // Recheck route using the existing database-backed route service.
        RouteDatabaseService routeService = new RouteDatabaseService();
        RouteOptimizer.RouteResult route =
                routeService.findRoute(warehouse, destination);

        if (route == null || route.path == null || route.path.isEmpty()
                || !Double.isFinite(route.distance)
                || route.distance < 0) {
            throw new SQLException(
                "No verified route exists from " + warehouse +
                " to " + destination + ".");
        }

        // Create the active commitment. Stock remains in physical inventory;
        // active deliveries reserve it until your existing delivery workflow
        // updates status and stock consistently.
        int deliveryId;

        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO deliveries " +
                "(request_id, vehicle_id, resource_name, quantity, status, " +
                "created_at, source_warehouse_id) " +
                "VALUES (?, ?, ?, ?, 'ASSIGNED', CURRENT_TIMESTAMP, ?)",
                Statement.RETURN_GENERATED_KEYS)) {

            ps.setInt(1, requestId);
            ps.setInt(2, vehicleId);
            ps.setString(3, resourceName.trim());
            ps.setInt(4, quantity);
            ps.setInt(5, warehouseId);

            ps.executeUpdate();

            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("Could not obtain delivery ID.");
                }
                deliveryId = keys.getInt(1);
            }
        }

        // Mark vehicle assigned only after the delivery insert succeeds.
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE vehicles SET status = 'ASSIGNED' " +
                "WHERE vehicle_id = ? AND UPPER(TRIM(status)) = 'AVAILABLE'")) {
            ps.setInt(1, vehicleId);

            if (ps.executeUpdate() != 1) {
                throw new SQLException(
                    "Vehicle was allocated concurrently. Please retry.");
            }
        }

        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO relief_decisions " +
                "(request_id, resource_name, vehicle_id, proposed_quantity, " +
                "decision, explanation, decided_by, delivery_id) " +
                "VALUES (?, ?, ?, ?, 'APPROVED', ?, ?, ?)")) {

            ps.setInt(1, requestId);
            ps.setString(2, resourceName.trim());
            ps.setInt(3, vehicleId);
            ps.setInt(4, quantity);
            ps.setString(5, explanation);
            ps.setString(6, decidedBy);
            ps.setInt(7, deliveryId);
            ps.executeUpdate();
        }
    }

    private void recordRejection(
            Connection connection,
            int requestId,
            String resourceName,
            int quantity,
            int vehicleId,
            String decidedBy,
            String explanation) throws SQLException {

        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO relief_decisions " +
                "(request_id, resource_name, vehicle_id, proposed_quantity, " +
                "decision, explanation, decided_by) " +
                "VALUES (?, ?, ?, ?, 'REJECTED', ?, ?)")) {

            ps.setInt(1, requestId);
            ps.setString(2, resourceName.trim());
            ps.setInt(3, vehicleId);
            ps.setInt(4, quantity);
            ps.setString(5, explanation);
            ps.setString(6, decidedBy);
            ps.executeUpdate();
        }
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return "Unexpected error.";
        }
        return message.replace('\n', ' ').replace('\r', ' ');
    }
}