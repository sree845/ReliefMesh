
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

@WebServlet("/coordination")
public class CoordinationServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest request,
                         HttpServletResponse response)
            throws ServletException, IOException {

        response.setContentType("text/html;charset=UTF-8");

        try (Connection con = Dbconnection.getConnection()) {

            int largestVehicleCapacity = 0;

            String vehicleSql =
                "SELECT COALESCE(MAX(capacity), 0) "
                + "FROM vehicles WHERE status = 'AVAILABLE'";

            try (PreparedStatement ps =
                     con.prepareStatement(vehicleSql);
                 ResultSet rs = ps.executeQuery()) {

                if (rs.next()) {
                    largestVehicleCapacity = rs.getInt(1);
                }
            }

            String requirementSql =
                "SELECT cr.resource_name, "
                + "SUM(cr.required_quantity) AS total_required, "
                + "MAX(rr.severity) AS highest_severity "
                + "FROM community_requirements cr "
                + "JOIN relief_requests rr "
                + "ON cr.request_id = rr.request_id "
                + "WHERE rr.status = 'PENDING' "
                + "GROUP BY cr.resource_name "
                + "ORDER BY highest_severity DESC";

            response.getWriter().println(
                "<!DOCTYPE html><html><head>"
                + "<title>ReliefMesh Coordination</title>"
                + "<style>"
                + "body{font-family:Arial;margin:30px;}"
                + "table{border-collapse:collapse;width:100%;}"
                + "th,td{border:1px solid #ccc;padding:10px;"
                + "text-align:left;}"
                + "th{background:#e8eef5;}"
                + ".warning{color:#b42318;font-weight:bold;}"
                + ".good{color:#187044;font-weight:bold;}"
                + "</style></head><body>"
                + "<h1>ReliefMesh - Coordination Engine</h1>"
                + "<p>Recommendations are based on pending demand, "
                + "inventory reservations, severity, and available "
                + "vehicle capacity.</p>"
                + "<p><b>Largest available vehicle capacity:</b> "
                + largestVehicleCapacity + "</p>"
                + "<table><tr>"
                + "<th>Resource</th>"
                + "<th>Pending Demand</th>"
                + "<th>Physical Stock</th>"
                + "<th>Reserved</th>"
                + "<th>Unreserved Stock</th>"
                + "<th>Highest Severity</th>"
                + "<th>Recommendation</th>"
                + "</tr>"
            );

            boolean found = false;

            try (PreparedStatement ps =
                     con.prepareStatement(requirementSql);
                 ResultSet rs = ps.executeQuery()) {

                while (rs.next()) {
                    found = true;

                    String resourceName =
                        rs.getString("resource_name");

                    int required =
                        rs.getInt("total_required");

                    int severity =
                        rs.getInt("highest_severity");

                    int physicalStock = getResourceStock(
                        con, resourceName
                    );

                    int reserved = getReservedQuantity(
                        con, resourceName
                    );

                    int unreserved = Math.max(
                        0, physicalStock - reserved
                    );

                    int feasibleQuantity = Math.min(
                        required,
                        Math.min(
                            unreserved,
                            largestVehicleCapacity
                        )
                    );

                    String recommendation;
                    String cssClass;

                    if (largestVehicleCapacity == 0) {
                        recommendation =
                            "No available vehicle. Keep the request "
                            + "pending and arrange transport.";
                        cssClass = "warning";

                    } else if (unreserved == 0) {
                        recommendation =
                            "No unreserved stock. Prioritize this "
                            + "need and seek additional supplies.";
                        cssClass = "warning";

                    } else if (physicalStock < required) {
                        recommendation =
                            "Shortage detected. Prioritize the "
                            + "highest-severity pending need. Up to "
                            + feasibleQuantity
                            + " units may be feasible now; seek "
                            + "additional supplies for the remainder.";
                        cssClass = "warning";

                    } else if (reserved > 0
                            && unreserved < required) {
                        recommendation =
                            "Some stock is already reserved. Up to "
                            + feasibleQuantity
                            + " units may be feasible from "
                            + "unreserved stock.";
                        cssClass = "warning";

                    } else {
                        recommendation =
                            "Sufficient unreserved stock for the "
                            + "pending demand. Up to "
                            + feasibleQuantity
                            + " units may be feasible with one "
                            + "available vehicle.";
                        cssClass = "good";
                    }

                    response.getWriter().println(
                        "<tr><td>"
                        + escapeHtml(resourceName)
                        + "</td><td>" + required
                        + "</td><td>" + physicalStock
                        + "</td><td>" + reserved
                        + "</td><td>" + unreserved
                        + "</td><td>" + severity
                        + "</td><td class=\"" + cssClass + "\">"
                        + escapeHtml(recommendation)
                        + "</td></tr>"
                    );
                }
            }

            if (!found) {
                response.getWriter().println(
                    "<tr><td colspan=\"7\">"
                    + "No pending community requirements found."
                    + "</td></tr>"
                );
            }

            response.getWriter().println(
                "</table>"
                + "<p><b>Note:</b> These are recommendations, "
                + "not automatic assignments. Confirm the individual "
                + "request, vehicle capacity, and stock before "
                + "creating a delivery.</p>"
                + "<p><a href=\""
                + request.getContextPath()
                + "/shortages\">Shortage Report</a> | "
                + "<a href=\""
                + request.getContextPath()
                + "/deliveries\">Delivery Management</a> | "
                + "<a href=\""
                + request.getContextPath()
                + "/dashboard\">Dashboard</a></p>"
                + "</body></html>"
            );

        } catch (SQLException e) {
            throw new ServletException(
                "Unable to generate coordination recommendations.", e
            );
        }
    }

    private int getResourceStock(Connection con, String resourceName)
            throws SQLException {

        String sql =
            "SELECT COALESCE(SUM(quantity), 0) "
            + "FROM resources WHERE resource_name = ?";

        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, resourceName);

            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private int getReservedQuantity(Connection con, String resourceName)
            throws SQLException {

        String sql =
            "SELECT COALESCE(SUM(quantity), 0) "
            + "FROM deliveries "
            + "WHERE resource_name = ? "
            + "AND status IN ('ASSIGNED', 'IN_TRANSIT')";

        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, resourceName);

            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private String escapeHtml(String value) {
        if (value == null) {
            return "";
        }

        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }
}
