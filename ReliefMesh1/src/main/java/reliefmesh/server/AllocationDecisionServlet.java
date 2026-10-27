
package reliefmesh.server;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import reliefmesh.util.Dbconnection;

@WebServlet("/api/coordination/decision-old")
public class AllocationDecisionServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    @Override
    protected void doPost(HttpServletRequest request,
                          HttpServletResponse response)
            throws IOException {

        response.setContentType("application/json;charset=UTF-8");

        String decision = request.getParameter("decision");
        String reason = request.getParameter("reason");
        int requestId;
        int quantity;
        String resource = request.getParameter("resourceName");

        try {
            requestId = Integer.parseInt(request.getParameter("requestId"));
            quantity = Integer.parseInt(request.getParameter("quantity"));
        } catch (Exception e) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.getWriter().print(
                "{\"error\":\"Valid requestId and quantity are required.\"}"
            );
            return;
        }


if (requestId <= 0
        || quantity < 0
        || resource == null
        || resource.isBlank()
        || decision == null
        || !(decision.equals("APPROVE")
             || decision.equals("REJECT"))
        || (decision.equals("APPROVE") && quantity <= 0)) {

    response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
    response.getWriter().print(
        "{\"error\":\"Invalid decision details.\"}"
    );
    return;
}


        if (reason == null) {
            reason = "";
        }
        if (reason.length() > 500) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.getWriter().print(
                "{\"error\":\"Reason must be at most 500 characters.\"}"
            );
            return;
        }

        try (Connection con = Dbconnection.getConnection()) {
            con.setAutoCommit(false);

            try {
                // Lock the request and its requirement row.
                int required;

                String requirementSql =
                    "SELECT cr.required_quantity " +
                    "FROM relief_requests rr " +
                    "JOIN community_requirements cr " +
                    "ON cr.request_id = rr.request_id " +
                    "WHERE rr.request_id = ? " +
                    "AND cr.resource_name = ? " +
                    "AND rr.status = 'PENDING' " +
                    "FOR UPDATE";

                try (PreparedStatement ps =
                        con.prepareStatement(requirementSql)) {
                    ps.setInt(1, requestId);
                    ps.setString(2, resource);

                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new IllegalArgumentException(
                                "Pending request/resource requirement not found."
                            );
                        }
                        required = rs.getInt("required_quantity");
                    }
                }

                if (decision.equals("REJECT")) {
                    recordDecision(con, requestId, resource, quantity,
                            "REJECTED", reason, null);
                    con.commit();

                    response.getWriter().print(
                        "{\"result\":\"REJECTED\"," +
                        "\"message\":\"Proposal rejected. No inventory changed.\"}"
                    );
                    return;
                }

                // Lock inventory rows and calculate physical stock.
                int physicalStock = 0;

                String inventorySql =
                    "SELECT quantity FROM resources " +
                    "WHERE resource_name = ? FOR UPDATE";

                try (PreparedStatement ps =
                        con.prepareStatement(inventorySql)) {
                    ps.setString(1, resource);

                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            physicalStock += rs.getInt("quantity");
                        }
                    }
                }

                int delivered = sumQuantity(con,
                    "SELECT COALESCE(SUM(quantity),0) FROM deliveries " +
                    "WHERE request_id=? AND resource_name=? " +
                    "AND status='DELIVERED'", requestId, resource);

                int committed = sumQuantity(con,
                    "SELECT COALESCE(SUM(quantity),0) FROM deliveries " +
                    "WHERE request_id=? AND resource_name=? " +
                    "AND status IN ('ASSIGNED','IN_TRANSIT')",
                    requestId, resource);

                int reserved = sumQuantity(con,
                    "SELECT COALESCE(SUM(quantity),0) FROM deliveries " +
                    "WHERE resource_name=? " +
                    "AND status IN ('ASSIGNED','IN_TRANSIT')",
                    resource);

                if (delivered + committed > required) {
                    throw new IllegalArgumentException(
                        "Recorded deliveries and commitments exceed the requirement."
                    );
                }

                if (quantity > required - delivered - committed) {
                    throw new IllegalArgumentException(
                        "Quantity exceeds the current outstanding need."
                    );
                }

                if (quantity > physicalStock - reserved) {
                    throw new IllegalArgumentException(
                        "Insufficient unreserved stock. Refresh the allocation plan."
                    );
                }

                // Lock and choose one actual available vehicle.
                int vehicleId = -1;
                int capacity = 0;

                String vehicleSql =
                    "SELECT vehicle_id, capacity FROM vehicles " +
                    "WHERE status='AVAILABLE' AND capacity >= ? " +
                    "ORDER BY capacity ASC, vehicle_id ASC " +
                    "LIMIT 1 FOR UPDATE";

                try (PreparedStatement ps =
                        con.prepareStatement(vehicleSql)) {
                    ps.setInt(1, quantity);

                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            vehicleId = rs.getInt("vehicle_id");
                            capacity = rs.getInt("capacity");
                        }
                    }
                }

                if (vehicleId < 0 || capacity < quantity) {
                    throw new IllegalArgumentException(
                        "No available vehicle has sufficient capacity."
                    );
                }

                int deliveryId;

                String insertSql =
                    "INSERT INTO deliveries " +
                    "(request_id, vehicle_id, resource_name, quantity, status) " +
                    "VALUES (?, ?, ?, ?, 'ASSIGNED')";

                try (PreparedStatement ps =
                        con.prepareStatement(insertSql,
                            PreparedStatement.RETURN_GENERATED_KEYS)) {
                    ps.setInt(1, requestId);
                    ps.setInt(2, vehicleId);
                    ps.setString(3, resource);
                    ps.setInt(4, quantity);

                    if (ps.executeUpdate() != 1) {
                        throw new SQLException("Delivery assignment failed.");
                    }

                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        if (!keys.next()) {
                            throw new SQLException("Delivery ID was not returned.");
                        }
                        deliveryId = keys.getInt(1);
                    }
                }

                try (PreparedStatement ps = con.prepareStatement(
                        "UPDATE vehicles SET status='ASSIGNED' " +
                        "WHERE vehicle_id=? AND status='AVAILABLE'")) {
                    ps.setInt(1, vehicleId);

                    if (ps.executeUpdate() != 1) {
                        throw new IllegalArgumentException(
                            "Vehicle is no longer available. Refresh and retry."
                        );
                    }
                }

                recordDecision(con, requestId, resource, quantity,
                        "APPROVED", reason, deliveryId);

                con.commit();

                response.getWriter().print(
                    "{\"result\":\"APPROVED\"," +
                    "\"deliveryId\":" + deliveryId + "," +
                    "\"vehicleId\":" + vehicleId + "," +
                    "\"status\":\"ASSIGNED\"," +
                    "\"message\":\"Approved and assigned. Stock is deducted only when delivered.\"}"
                );

            } catch (IllegalArgumentException e) {
                rollback(con);
                response.setStatus(HttpServletResponse.SC_CONFLICT);
                response.getWriter().print(
                    "{\"error\":\"" + json(e.getMessage()) + "\"}"
                );
            } catch (Exception e) {
                rollback(con);
                getServletContext().log(
                    "Allocation decision failed", e);
                response.setStatus(
                    HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                response.getWriter().print(
                    "{\"error\":\"Decision failed. Check server logs.\"}"
                );
            } finally {
                try {
                    con.setAutoCommit(true);
                } catch (SQLException ignored) {
                    // Connection will be closed.
                }
            }

        } catch (SQLException e) {
            getServletContext().log("Database connection failed", e);
            response.setStatus(
                HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.getWriter().print(
                "{\"error\":\"Database operation failed.\"}"
            );
        }
    }

    private int sumQuantity(Connection con, String sql,
                            int requestId, String resource)
            throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, requestId);
            ps.setString(2, resource);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private int sumQuantity(Connection con, String sql, String resource)
            throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, resource);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private void recordDecision(Connection con, int requestId,
                                String resource, int quantity,
                                String decision, String reason,
                                Integer deliveryId)
            throws SQLException {
        String sql =
            "INSERT INTO allocation_decisions " +
            "(request_id, resource_name, proposed_quantity, decision, " +
            "explanation, delivery_id) VALUES (?, ?, ?, ?, ?, ?)";

        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, requestId);
            ps.setString(2, resource);
            ps.setInt(3, quantity);
            ps.setString(4, decision);
            ps.setString(5, reason);

            if (deliveryId == null) {
                ps.setNull(6, java.sql.Types.INTEGER);
            } else {
                ps.setInt(6, deliveryId);
            }

            ps.executeUpdate();
        }
    }

    private void rollback(Connection con) {
        try {
            con.rollback();
        } catch (SQLException ignored) {
            // Preserve the original error.
        }
    }

    private String json(String value) {
        return value.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
    }
}
