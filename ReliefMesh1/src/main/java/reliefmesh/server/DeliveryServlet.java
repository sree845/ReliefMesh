
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

@WebServlet("/deliveries")
public class DeliveryServlet extends HttpServlet {

    private static final String[] ALLOWED_RESOURCES = {
        "Food Kits", "Medical Kits", "Blankets"
    };

    @Override
    protected void doGet(HttpServletRequest request,
                         HttpServletResponse response)
            throws IOException {

        response.setContentType("text/html;charset=UTF-8");

        try (Connection con = Dbconnection.getConnection()) {
            var out = response.getWriter();
            String context = request.getContextPath();

            out.println(
                "<!DOCTYPE html><html><head><meta charset='UTF-8'>" +
                "<title>ReliefMesh Deliveries</title>" +
                "<style>" +
                "body{font-family:Arial;margin:30px;background:#f4f6f8}" +
                "table{border-collapse:collapse;width:100%;background:white}" +
                "th,td{padding:10px;border:1px solid #ddd;text-align:left}" +
                "th{background:#17365d;color:white}" +
                "input,select,button{padding:8px;margin:5px}" +
                "form{margin:0}" +
                "</style></head><body>" +
                "<h1>ReliefMesh - Delivery Management</h1>" +
                "<h2>Create Delivery Assignment</h2>" +
                "<form method='post' action='" + context + "/deliveries'>" +
                "<label>Relief Request:</label><br>" +
                "<select name='requestId' required>"
            );

            String requestSql =
                "SELECT request_id, location, emergency_type, status " +
                "FROM relief_requests " +
                "WHERE status <> 'FULFILLED' " +
                "ORDER BY severity DESC, request_id";

            try (PreparedStatement ps = con.prepareStatement(requestSql);
                 ResultSet rs = ps.executeQuery()) {

                while (rs.next()) {
                    int id = rs.getInt("request_id");
                    String status = rs.getString("status");

                    out.println(
                        "<option value='" + id + "'>" +
                        "Request #" + id + " - " +
                        escape(rs.getString("emergency_type")) + " (" +
                        escape(rs.getString("location")) + ") - " +
                        escape(status) + "</option>"
                    );
                }
            }

            out.println(
                "</select><br><label>Vehicle:</label><br>" +
                "<select name='vehicleId' required>"
            );

            String vehicleSql =
                "SELECT vehicle_id, vehicle_name, capacity " +
                "FROM vehicles WHERE status = 'AVAILABLE' " +
                "ORDER BY vehicle_id";

            try (PreparedStatement ps = con.prepareStatement(vehicleSql);
                 ResultSet rs = ps.executeQuery()) {

                while (rs.next()) {
                    out.println(
                        "<option value='" + rs.getInt("vehicle_id") + "'>" +
                        escape(rs.getString("vehicle_name")) +
                        " - Capacity: " + rs.getInt("capacity") +
                        "</option>"
                    );
                }
            }

            out.println(
                "</select><br><label>Resource:</label><br>" +
                "<select name='resourceName' required>"
            );

            for (String resource : ALLOWED_RESOURCES) {
                out.println(
                    "<option value='" + escape(resource) + "'>" +
                    escape(resource) + "</option>"
                );
            }

            out.println(
                "</select><br><label>Quantity:</label><br>" +
                "<input type='number' name='quantity' min='1' required>" +
                "<br><button type='submit'>Assign Delivery</button>" +
                "</form><hr><h2>Delivery Records</h2>" +
                "<table><tr><th>Delivery ID</th><th>Request ID</th>" +
                "<th>Vehicle</th><th>Resource</th><th>Quantity</th>" +
                "<th>Status</th><th>Update Status</th></tr>"
            );

            String deliverySql =
                "SELECT d.delivery_id, d.request_id, v.vehicle_name, " +
                "d.resource_name, d.quantity, d.status " +
                "FROM deliveries d " +
                "JOIN vehicles v ON d.vehicle_id = v.vehicle_id " +
                "ORDER BY d.delivery_id DESC";

            boolean found = false;

            try (PreparedStatement ps = con.prepareStatement(deliverySql);
                 ResultSet rs = ps.executeQuery()) {

                while (rs.next()) {
                    found = true;

                    int id = rs.getInt("delivery_id");
                    String status = rs.getString("status");

                    out.println(
                        "<tr><td>" + id +
                        "</td><td>" + rs.getInt("request_id") +
                        "</td><td>" + escape(rs.getString("vehicle_name")) +
                        "</td><td>" + escape(rs.getString("resource_name")) +
                        "</td><td>" + rs.getInt("quantity") +
                        "</td><td>" + escape(status) + "</td><td>"
                    );

                    if ("DELIVERED".equals(status)
                            || "CANCELLED".equals(status)) {
                        out.println("Closed");
                    } else if ("ASSIGNED".equals(status)
                            || "IN_TRANSIT".equals(status)) {

                        out.println(
                            "<form method='post' action='" +
                            context + "/delivery-status'>" +
                            "<input type='hidden' name='deliveryId' value='" +
                            id + "'><select name='status' required>"
                        );

                        if ("ASSIGNED".equals(status)) {
                            out.println(
                                "<option value='IN_TRANSIT'>In Transit</option>"
                            );
                        }

                        out.println(
                            "<option value='DELIVERED'>Delivered</option>" +
                            "<option value='CANCELLED'>Cancelled</option>" +
                            "</select><button type='submit'>Update</button>" +
                            "</form>"
                        );
                    } else {
                        out.println("Unknown status: " + escape(status));
                    }

                    out.println("</td></tr>");
                }
            }

            if (!found) {
                out.println(
                    "<tr><td colspan='7'>No deliveries assigned yet.</td></tr>"
                );
            }

            out.println(
                "</table><br><a href='" + context +
                "/dashboard'>Relief Dashboard</a> | " +
                "<a href='" + context +
                "/shortages'>Shortage Report</a></body></html>"
            );

        } catch (SQLException e) {
            getServletContext().log(
                "Could not load delivery management page.", e
            );
            response.sendError(
                HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "Unable to load delivery management. Check the server log."
            );
        }
    }

    @Override
    protected void doPost(HttpServletRequest request,
                          HttpServletResponse response)
            throws IOException {

        int requestId;
        int vehicleId;
        int quantity;

        try {
            requestId = Integer.parseInt(request.getParameter("requestId"));
            vehicleId = Integer.parseInt(request.getParameter("vehicleId"));
            quantity = Integer.parseInt(request.getParameter("quantity"));

            if (requestId <= 0 || vehicleId <= 0 || quantity <= 0) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException e) {
            response.sendError(
                HttpServletResponse.SC_BAD_REQUEST,
                "Enter valid positive request, vehicle, and quantity values."
            );
            return;
        }

        String resourceName = request.getParameter("resourceName");

        if (!isAllowedResource(resourceName)) {
            response.sendError(
                HttpServletResponse.SC_BAD_REQUEST,
                "Invalid resource selected."
            );
            return;
        }

        try (Connection con = Dbconnection.getConnection()) {
            con.setAutoCommit(false);

            try {
                /*
                 * Lock inventory first, matching DeliveryStatusServlet.
                 */
                int totalStock = 0;
                boolean inventoryExists = false;

                String stockSql =
                    "SELECT resource_id, quantity FROM resources " +
                    "WHERE resource_name = ? " +
                    "ORDER BY resource_id FOR UPDATE";

                try (PreparedStatement ps = con.prepareStatement(stockSql)) {
                    ps.setString(1, resourceName);

                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            inventoryExists = true;
                            int stock = rs.getInt("quantity");

                            if (stock < 0) {
                                throw new SQLException(
                                    "Negative inventory detected."
                                );
                            }

                            totalStock = Math.addExact(totalStock, stock);
                        }
                    }
                }

                if (!inventoryExists) {
                    throw new SQLException(
                        "No inventory record exists for " + resourceName + "."
                    );
                }

                /*
                 * Active assignments reserve stock; they do not deduct it.
                 */
                String reservedSql =
                    "SELECT COALESCE(SUM(quantity), 0) " +
                    "FROM deliveries WHERE resource_name = ? " +
                    "AND status IN ('ASSIGNED', 'IN_TRANSIT')";

                int reserved;

                try (PreparedStatement ps = con.prepareStatement(reservedSql)) {
                    ps.setString(1, resourceName);

                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        reserved = rs.getInt(1);
                    }
                }

                int available = totalStock - reserved;

                if (available < 0) {
                    throw new SQLException(
                        "Inventory is overcommitted. Review existing deliveries."
                    );
                }

                if (quantity > available) {
                    throw new SQLException(
                        "Insufficient unreserved stock for " + resourceName +
                        ". Available: " + available +
                        ", requested: " + quantity + "."
                    );
                }

                /*
                 * Lock and validate the vehicle.
                 */
                int capacity;

                String vehicleSql =
                    "SELECT capacity, status FROM vehicles " +
                    "WHERE vehicle_id = ? FOR UPDATE";

                try (PreparedStatement ps = con.prepareStatement(vehicleSql)) {
                    ps.setInt(1, vehicleId);

                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new SQLException("Vehicle not found.");
                        }

                        capacity = rs.getInt("capacity");
                        String status = rs.getString("status");

                        if (!"AVAILABLE".equals(status)) {
                            throw new SQLException(
                                "Vehicle is no longer available. Refresh the page."
                            );
                        }
                    }
                }

                if (quantity > capacity) {
                    throw new SQLException(
                        "Quantity exceeds vehicle capacity of " + capacity + "."
                    );
                }

                /*
                 * Lock the request and reject requests that need review
                 * or have already been fulfilled.
                 */
                String requestSql =
                    "SELECT status FROM relief_requests " +
                    "WHERE request_id = ? FOR UPDATE";

                String requestStatus;

                try (PreparedStatement ps = con.prepareStatement(requestSql)) {
                    ps.setInt(1, requestId);

                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new SQLException("Relief request not found.");
                        }
                        requestStatus = rs.getString("status");
                    }
                }

                if ("FULFILLED".equals(requestStatus)) {
                    throw new SQLException(
                        "This request is already fulfilled."
                    );
                }

                if ("REVIEW_REQUIRED".equals(requestStatus)) {
                    throw new SQLException(
                        "This request has a data conflict and requires review."
                    );
                }

                /*
                 * Validate that this request actually needs this resource.
                 */
                String requirementSql =
                    "SELECT required_quantity FROM community_requirements " +
                    "WHERE request_id = ? AND resource_name = ? FOR UPDATE";

                int required;

                try (PreparedStatement ps = con.prepareStatement(requirementSql)) {
                    ps.setInt(1, requestId);
                    ps.setString(2, resourceName);

                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new SQLException(
                                "This request has no requirement for " +
                                resourceName + "."
                            );
                        }
                        required = rs.getInt("required_quantity");
                    }
                }

                String deliveredSql =
                    "SELECT COALESCE(SUM(quantity), 0) FROM deliveries " +
                    "WHERE request_id = ? AND resource_name = ? " +
                    "AND status = 'DELIVERED'";

                int delivered;

                try (PreparedStatement ps = con.prepareStatement(deliveredSql)) {
                    ps.setInt(1, requestId);
                    ps.setString(2, resourceName);

                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        delivered = rs.getInt(1);
                    }
                }

                String committedSql =
                    "SELECT COALESCE(SUM(quantity), 0) FROM deliveries " +
                    "WHERE request_id = ? AND resource_name = ? " +
                    "AND status IN ('ASSIGNED', 'IN_TRANSIT')";

                int committed;

                try (PreparedStatement ps = con.prepareStatement(committedSql)) {
                    ps.setInt(1, requestId);
                    ps.setString(2, resourceName);

                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        committed = rs.getInt(1);
                    }
                }

                if (delivered > required) {
                    throw new SQLException(
                        "Delivered quantity already exceeds the requirement. " +
                        "Review this request before assigning more."
                    );
                }

                int outstanding = required - delivered - committed;

                if (outstanding <= 0) {
                    throw new SQLException(
                        "This requirement is already covered by deliveries " +
                        "and active commitments."
                    );
                }

                if (quantity > outstanding) {
                    throw new SQLException(
                        "Quantity exceeds the outstanding need of " +
                        outstanding + " units for this request."
                    );
                }

                /*
                 * Create assignment and reserve vehicle atomically.
                 */
                String insertSql =
                    "INSERT INTO deliveries " +
                    "(request_id, vehicle_id, resource_name, quantity, status) " +
                    "VALUES (?, ?, ?, ?, 'ASSIGNED')";

                try (PreparedStatement ps = con.prepareStatement(insertSql)) {
                    ps.setInt(1, requestId);
                    ps.setInt(2, vehicleId);
                    ps.setString(3, resourceName);
                    ps.setInt(4, quantity);

                    if (ps.executeUpdate() != 1) {
                        throw new SQLException("Could not create delivery.");
                    }
                }

                String updateVehicleSql =
                    "UPDATE vehicles SET status = 'ASSIGNED' " +
                    "WHERE vehicle_id = ? AND status = 'AVAILABLE'";

                try (PreparedStatement ps =
                         con.prepareStatement(updateVehicleSql)) {
                    ps.setInt(1, vehicleId);

                    if (ps.executeUpdate() != 1) {
                        throw new SQLException(
                            "Vehicle assignment failed. Please retry."
                        );
                    }
                }

                con.commit();

            } catch (SQLException | RuntimeException e) {
                try {
                    con.rollback();
                } catch (SQLException rollbackError) {
                    e.addSuppressed(rollbackError);
                }
                throw e;
            } finally {
                try {
                    con.setAutoCommit(true);
                } catch (SQLException ignored) {
                    // Connection is closing.
                }
            }

            response.sendRedirect(request.getContextPath() + "/deliveries");

        } catch (SQLException e) {
            getServletContext().log("Delivery assignment failed.", e);
            response.setContentType("text/html;charset=UTF-8");
            response.setStatus(HttpServletResponse.SC_CONFLICT);

            response.getWriter().println(
                "<!DOCTYPE html><html><head><meta charset='UTF-8'>" +
                "<title>Assignment Error</title></head><body>" +
                "<h2>Unable to Create Delivery</h2><p>" +
                escape(e.getMessage()) + "</p><a href='" +
                request.getContextPath() +
                "/deliveries'>Back to Delivery Management</a></body></html>"
            );
        }
    }

    private boolean isAllowedResource(String resourceName) {
        if (resourceName == null) {
            return false;
        }

        for (String allowed : ALLOWED_RESOURCES) {
            if (allowed.equals(resourceName)) {
                return true;
            }
        }

        return false;
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }

        return value.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;")
                    .replace("'", "&#39;");
    }
}
