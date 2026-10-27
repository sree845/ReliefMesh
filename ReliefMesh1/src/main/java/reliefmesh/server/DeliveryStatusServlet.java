
package reliefmesh.server;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import reliefmesh.util.Dbconnection;

@WebServlet("/delivery-status")
public class DeliveryStatusServlet extends HttpServlet {

    @Override
    protected void doPost(HttpServletRequest request,
                          HttpServletResponse response)
            throws ServletException, IOException {

        response.setContentType("text/plain;charset=UTF-8");

        int deliveryId;
        try {
            deliveryId = Integer.parseInt(
                    request.getParameter("deliveryId"));
        } catch (Exception e) {
            response.setStatus(400);
            response.getWriter().write("Invalid deliveryId.");
            return;
        }

        String target = request.getParameter("status");
        if (target == null ||
                !(target.equals("IN_TRANSIT")
                || target.equals("DELIVERED")
                || target.equals("CANCELLED"))) {
            response.setStatus(400);
            response.getWriter().write("Invalid target status.");
            return;
        }

        try (Connection con = Dbconnection.getConnection()) {
            con.setAutoCommit(false);

            try {
                int requestId;
                int vehicleId;
                int sourceResourceId;
                int quantity;
                String resourceName;
                String oldStatus;

                String lockSql =
                    "SELECT request_id, vehicle_id, resource_name, " +
                    "quantity, status, source_warehouse_id " +
                    "FROM deliveries WHERE delivery_id = ? FOR UPDATE";

                try (PreparedStatement ps =
                        con.prepareStatement(lockSql)) {
                    ps.setInt(1, deliveryId);

                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new SQLException("Delivery not found.");
                        }

                        requestId = rs.getInt("request_id");
                        vehicleId = rs.getInt("vehicle_id");
                        resourceName = rs.getString("resource_name");
                        quantity = rs.getInt("quantity");
                        oldStatus = rs.getString("status");

                        sourceResourceId =
                            rs.getInt("source_warehouse_id");

                        if (rs.wasNull()) {
                            sourceResourceId = 0;
                        }
                    }
                }

                if (oldStatus.equals("DELIVERED")
                        || oldStatus.equals("CANCELLED")) {
                    throw new SQLException(
                            "This delivery is already closed.");
                }

                boolean validTransition =
                    (oldStatus.equals("ASSIGNED")
                        && (target.equals("IN_TRANSIT")
                            || target.equals("DELIVERED")
                            || target.equals("CANCELLED")))
                    || (oldStatus.equals("IN_TRANSIT")
                        && (target.equals("DELIVERED")
                            || target.equals("CANCELLED")));

                if (!validTransition) {
                    throw new SQLException(
                            "Invalid delivery status transition.");
                }

                if (target.equals("DELIVERED")) {
                    if (sourceResourceId <= 0) {
                        throw new SQLException(
                            "This legacy delivery has no recorded " +
                            "source warehouse. Review its source before " +
                            "marking it delivered.");
                    }

                    int stock;
                    try (PreparedStatement ps = con.prepareStatement(
                            "SELECT quantity, resource_name " +
                            "FROM resources WHERE resource_id = ? " +
                            "FOR UPDATE")) {
                        ps.setInt(1, sourceResourceId);

                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) {
                                throw new SQLException(
                                        "Source stock record not found.");
                            }

                            stock = rs.getInt("quantity");
                            String actualName =
                                rs.getString("resource_name");

                            if (!resourceName.equals(actualName)) {
                                throw new SQLException(
                                    "Source warehouse resource mismatch.");
                            }
                        }
                    }

                    if (stock < quantity) {
                        throw new SQLException(
                                "Insufficient stock at the source warehouse.");
                    }

                    try (PreparedStatement ps = con.prepareStatement(
                            "UPDATE resources SET quantity = quantity - ? " +
                            "WHERE resource_id = ? AND quantity >= ?")) {
                        ps.setInt(1, quantity);
                        ps.setInt(2, sourceResourceId);
                        ps.setInt(3, quantity);

                        if (ps.executeUpdate() != 1) {
                            throw new SQLException(
                                    "Stock update failed.");
                        }
                    }
                }

                try (PreparedStatement ps = con.prepareStatement(
                        "UPDATE deliveries SET status = ? " +
                        "WHERE delivery_id = ? AND status = ?")) {
                    ps.setString(1, target);
                    ps.setInt(2, deliveryId);
                    ps.setString(3, oldStatus);

                    if (ps.executeUpdate() != 1) {
                        throw new SQLException(
                                "Delivery status changed concurrently.");
                    }
                }

                if (target.equals("DELIVERED")
                        || target.equals("CANCELLED")) {

                    boolean otherActive;

                    try (PreparedStatement ps = con.prepareStatement(
                            "SELECT COUNT(*) FROM deliveries " +
                            "WHERE vehicle_id = ? AND delivery_id <> ? " +
                            "AND status IN ('ASSIGNED', 'IN_TRANSIT')")) {
                        ps.setInt(1, vehicleId);
                        ps.setInt(2, deliveryId);

                        try (ResultSet rs = ps.executeQuery()) {
                            rs.next();
                            otherActive = rs.getInt(1) > 0;
                        }
                    }

                    if (!otherActive) {
                        try (PreparedStatement ps = con.prepareStatement(
                                "UPDATE vehicles SET status = 'AVAILABLE' " +
                                "WHERE vehicle_id = ?")) {
                            ps.setInt(1, vehicleId);
                            ps.executeUpdate();
                        }
                    }

                    if (target.equals("DELIVERED")) {
                        RequestStatusService.reconcile(con, requestId);
                    }
                }

                con.commit();
                response.setStatus(HttpServletResponse.SC_OK);
                response.getWriter().write(
                        "Delivery " + deliveryId +
                        " updated to " + target + ".");

            } catch (Exception e) {
                try {
                    con.rollback();
                } catch (SQLException ignored) {
                }

                response.setStatus(
                        e instanceof SQLException ? 409 : 500);
                response.getWriter().write(
                        e.getMessage() == null
                        ? "Delivery update failed."
                        : e.getMessage());
            } finally {
                try {
                    con.setAutoCommit(true);
                } catch (SQLException ignored) {
                }
            }

        } catch (SQLException e) {
            response.setStatus(500);
            response.getWriter().write("Database connection failed.");
        }
    }
}
