
package reliefmesh.server;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.TreeMap;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import reliefmesh.util.Dbconnection;

@WebServlet("/api/coordination")
public class CoordinationApiServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    private static class ResourceSummary {
        String name;
        int demand;
        int stock;
        int reserved;
        int severity;
        int conflicts;

        ResourceSummary(String name) {
            this.name = name;
        }
    }

    @Override
    protected void doGet(HttpServletRequest request,
                         HttpServletResponse response)
            throws IOException {

        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");

        try (Connection con = Dbconnection.getConnection()) {

            Map<String, ResourceSummary> summaries = new TreeMap<>();

            // Read actual unmet demand per pending request.
            String demandSql =
                "SELECT rr.request_id, rr.severity, " +
                "cr.resource_name, cr.required_quantity, " +
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

            try (PreparedStatement ps = con.prepareStatement(demandSql);
                 ResultSet rs = ps.executeQuery()) {

                while (rs.next()) {

                    String name = rs.getString("resource_name");
                    ResourceSummary s = summaries.computeIfAbsent(
                        name, ResourceSummary::new
                    );

                    int required = rs.getInt("required_quantity");
                    int delivered = rs.getInt("delivered");
                    int committed = rs.getInt("committed");

                    // Do not count fulfilled or committed demand twice.
                    int outstanding = Math.max(
                        0, required - delivered - committed
                    );

                    s.demand += outstanding;
                    s.severity = Math.max(
                        s.severity, rs.getInt("severity")
                    );

                    if (delivered + committed > required) {
                        s.conflicts++;
                    }
                }
            }

            // Read physical inventory separately to avoid JOIN multiplication.
            try (PreparedStatement ps = con.prepareStatement(
                    "SELECT resource_name, SUM(quantity) AS stock " +
                    "FROM resources GROUP BY resource_name");
                 ResultSet rs = ps.executeQuery()) {

                while (rs.next()) {
                    String name = rs.getString("resource_name");
                    ResourceSummary s = summaries.computeIfAbsent(
                        name, ResourceSummary::new
                    );
                    s.stock = rs.getInt("stock");
                }
            }

            // Reservations include assigned and in-transit deliveries.
            try (PreparedStatement ps = con.prepareStatement(
                    "SELECT resource_name, SUM(quantity) AS reserved " +
                    "FROM deliveries " +
                    "WHERE status IN ('ASSIGNED','IN_TRANSIT') " +
                    "GROUP BY resource_name");
                 ResultSet rs = ps.executeQuery()) {

                while (rs.next()) {
                    String name = rs.getString("resource_name");
                    ResourceSummary s = summaries.computeIfAbsent(
                        name, ResourceSummary::new
                    );
                    s.reserved = rs.getInt("reserved");
                }
            }

            int availableVehicles = scalar(con,
                "SELECT COUNT(*) FROM vehicles WHERE status='AVAILABLE'");

            int largestCapacity = scalar(con,
                "SELECT COALESCE(MAX(capacity),0) " +
                "FROM vehicles WHERE status='AVAILABLE'");

            response.getWriter().print(
                "{\"availableVehicles\":" + availableVehicles +
                ",\"largestVehicleCapacity\":" + largestCapacity +
                ",\"resources\":["
            );

            boolean first = true;

            for (ResourceSummary s : summaries.values()) {

                int unreserved = Math.max(0, s.stock - s.reserved);
                int shortage = Math.max(0, s.demand - unreserved);

                int feasible = availableVehicles == 0
                    ? 0
                    : Math.min(
                        s.demand,
                        Math.min(unreserved, largestCapacity)
                    );

                String recommendation;

                if (s.conflicts > 0) {
                    recommendation =
                        "Review " + s.conflicts +
                        " request(s) with delivered plus committed " +
                        "quantities exceeding their requirement. " +
                        "Do not allocate further to conflicting requests.";
                } else if (s.demand == 0) {
                    recommendation =
                        "No outstanding demand remains for this resource.";
                } else if (availableVehicles == 0) {
                    recommendation =
                        "Unmet demand remains, but no vehicle is available. " +
                        "Protect existing commitments and seek transport.";
                } else if (unreserved == 0) {
                    recommendation =
                        "No unreserved stock is available. Seek replenishment " +
                        "or evaluate another warehouse.";
                } else if (shortage > 0) {
                    recommendation =
                        "Shortage of " + shortage +
                        " units against outstanding demand. Prioritize " +
                        "individual requests by severity and feasibility.";
                } else {
                    recommendation =
                        "Stock appears sufficient for outstanding demand. " +
                        "Validate each request, vehicle, and route before assignment.";
                }

                if (!first) {
                    response.getWriter().print(",");
                }
                first = false;

                response.getWriter().print(
                    "{\"resourceName\":\"" + json(s.name) + "\"" +
                    ",\"pendingDemand\":" + s.demand +
                    ",\"physicalStock\":" + s.stock +
                    ",\"reservedQuantity\":" + s.reserved +
                    ",\"unreservedStock\":" + unreserved +
                    ",\"highestSeverity\":" + s.severity +
                    ",\"shortage\":" + shortage +
                    ",\"feasibleQuantity\":" + feasible +
                    ",\"dataConflictCount\":" + s.conflicts +
                    ",\"recommendation\":\"" +
                    json(recommendation) + "\"}"
                );
            }

            response.getWriter().print("]}");

        } catch (SQLException e) {
            getServletContext().log(
                "Coordination evaluation failed", e
            );

            response.setStatus(
                HttpServletResponse.SC_INTERNAL_SERVER_ERROR
            );

            response.getWriter().print(
                "{\"error\":\"Coordination evaluation failed. " +
                "Check the Eclipse server log.\"}"
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

    private String json(String value) {
        if (value == null) return "";

        return value.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }
}
