
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

@WebServlet("/api/coordination/requests")
public class RequestCoordinationApiServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet(HttpServletRequest request,
                         HttpServletResponse response)
            throws IOException {

        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");

        String sql =
            "SELECT rr.request_id, rr.requester_name, rr.location, " +
            "rr.emergency_type, rr.severity, cr.resource_name, " +
            "cr.required_quantity, " +
            "COALESCE((SELECT SUM(d.quantity) FROM deliveries d " +
            "WHERE d.request_id=rr.request_id " +
            "AND d.resource_name=cr.resource_name " +
            "AND d.status='DELIVERED'),0) AS delivered, " +
            "COALESCE((SELECT SUM(d.quantity) FROM deliveries d " +
            "WHERE d.request_id=rr.request_id " +
            "AND d.resource_name=cr.resource_name " +
            "AND d.status IN ('ASSIGNED','IN_TRANSIT')),0) AS committed " +
            "FROM relief_requests rr " +
            "JOIN community_requirements cr " +
            "ON cr.request_id=rr.request_id " +
            "WHERE rr.status='PENDING' " +
            "ORDER BY rr.severity DESC, rr.request_id ASC";

        try (Connection con = Dbconnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            int vehicles = scalar(con,
                "SELECT COUNT(*) FROM vehicles WHERE status='AVAILABLE'");

            int capacity = scalar(con,
                "SELECT COALESCE(MAX(capacity),0) FROM vehicles " +
                "WHERE status='AVAILABLE'");

            response.getWriter().print(
                "{\"availableVehicles\":" + vehicles +
                ",\"largestAvailableVehicleCapacity\":" + capacity +
                ",\"items\":["
            );

            boolean first = true;

            while (rs.next()) {

                int id = rs.getInt("request_id");
                String resource = rs.getString("resource_name");
                int required = rs.getInt("required_quantity");
                int delivered = rs.getInt("delivered");
                int committed = rs.getInt("committed");

                int outstanding = Math.max(
                    0, required - delivered - committed
                );

                int stock = stock(con, resource);
                int reserved = reserved(con, resource);
                int unreserved = Math.max(0, stock - reserved);

                int feasible = vehicles == 0 ? 0 : Math.min(
                    outstanding, Math.min(unreserved, capacity)
                );

                boolean conflict = delivered + committed > required;

                String outcome;
                String explanation;

                if (conflict) {
                    outcome = "REVIEW_DATA";
                    explanation =
                        "Delivered quantity plus active commitments " +
                        "exceeds the recorded requirement. Review this " +
                        "request before assigning more relief.";
                } else if (outstanding == 0) {
                    outcome = "COVERED";
                    explanation =
                        "No outstanding quantity remains after accounting " +
                        "for delivered relief and active commitments.";
                } else if (vehicles == 0) {
                    outcome = "NO_VEHICLE";
                    explanation =
                        "Unmet demand remains, but no vehicle is available.";
                } else if (unreserved == 0) {
                    outcome = "NO_UNRESERVED_STOCK";
                    explanation =
                        "Unmet demand remains, but no unreserved stock " +
                        "is currently available.";
                } else if (feasible < outstanding) {
                    outcome = "PARTIAL_FEASIBILITY";
                    explanation =
                        "At most " + feasible +
                        " units pass the current stock and capacity checks. " +
                        "The remainder stays unmet.";
                } else {
                    outcome = "FEASIBLE_CANDIDATE";
                    explanation =
                        "Stock and vehicle-capacity checks pass. " +
                        "Route feasibility and current inventory must be " +
                        "rechecked before delivery creation.";
                }

                if (!first) response.getWriter().print(",");
                first = false;

                response.getWriter().print(
                    "{\"requestId\":" + id +
                    ",\"requesterName\":\"" +
                    json(rs.getString("requester_name")) + "\"" +
                    ",\"location\":\"" + json(rs.getString("location")) + "\"" +
                    ",\"emergencyType\":\"" +
                    json(rs.getString("emergency_type")) + "\"" +
                    ",\"severity\":" + rs.getInt("severity") +
                    ",\"resourceName\":\"" + json(resource) + "\"" +
                    ",\"requiredQuantity\":" + required +
                    ",\"deliveredQuantity\":" + delivered +
                    ",\"committedQuantity\":" + committed +
                    ",\"outstandingQuantity\":" + outstanding +
                    ",\"physicalStock\":" + stock +
                    ",\"reservedStock\":" + reserved +
                    ",\"unreservedStock\":" + unreserved +
                    ",\"feasibleQuantity\":" + feasible +
                    ",\"overFulfilledOrOverCommitted\":" + conflict +
                    ",\"outcome\":\"" + outcome + "\"" +
                    ",\"explanation\":\"" + json(explanation) + "\"}"
                );
            }

            response.getWriter().print("]}");

        } catch (SQLException e) {
            getServletContext().log(
                "Request-level coordination failed", e
            );

            response.setStatus(
                HttpServletResponse.SC_INTERNAL_SERVER_ERROR
            );

            response.getWriter().print(
                "{\"error\":\"Request-level coordination failed.\"}"
            );
        }
    }

    private int scalar(Connection con, String sql)
            throws SQLException {

        try (PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private int stock(Connection con, String name)
            throws SQLException {

        try (PreparedStatement ps = con.prepareStatement(
                "SELECT COALESCE(SUM(quantity),0) FROM resources " +
                "WHERE resource_name=?")) {

            ps.setString(1, name);

            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private int reserved(Connection con, String name)
            throws SQLException {

        try (PreparedStatement ps = con.prepareStatement(
                "SELECT COALESCE(SUM(quantity),0) FROM deliveries " +
                "WHERE resource_name=? " +
                "AND status IN ('ASSIGNED','IN_TRANSIT')")) {

            ps.setString(1, name);

            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private String json(String value) {
        if (value == null) return "";

        return value.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }
}
