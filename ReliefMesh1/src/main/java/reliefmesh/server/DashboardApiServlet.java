
package reliefmesh.server;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import reliefmesh.util.Dbconnection;

@WebServlet("/api/dashboard")
public class DashboardApiServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet(HttpServletRequest request,
                         HttpServletResponse response)
            throws IOException {

        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");

        try (Connection con = Dbconnection.getConnection()) {

            int pendingRequests = count(con,
                "SELECT COUNT(*) FROM relief_requests WHERE status = 'PENDING'");

            int foodKits = availableStock(con, "Food Kits");
            int medicalKits = availableStock(con, "Medical Kits");

            int availableVehicles = count(con,
                "SELECT COUNT(*) FROM vehicles WHERE status = 'AVAILABLE'");

            response.getWriter().printf(
                "{\"pendingRequests\":%d,\"foodKits\":%d,"
                + "\"medicalKits\":%d,\"availableVehicles\":%d}",
                pendingRequests, foodKits, medicalKits, availableVehicles
            );

        } catch (Exception e) {
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.getWriter().write(
                "{\"error\":\"Unable to load dashboard data\"}"
            );
            getServletContext().log("Dashboard API failed", e);
        }
    }

    private int count(Connection con, String sql) throws Exception {
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private int availableStock(Connection con, String resourceName)
            throws Exception {

        String sql =
            "SELECT COALESCE(SUM(quantity), 0) FROM resources "
            + "WHERE resource_name = ?";

        int physicalStock;

        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, resourceName);

            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                physicalStock = rs.getInt(1);
            }
        }


String reservedSql =
    "SELECT COALESCE(SUM(quantity), 0) "
    + "FROM deliveries "
    + "WHERE resource_name = ? "
    + "AND status IN ('ASSIGNED', 'IN_TRANSIT')";

        int reserved = 0;

        try (PreparedStatement ps = con.prepareStatement(reservedSql)) {
            ps.setString(1, resourceName);

            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                reserved = rs.getInt(1);
            }
        }

        return Math.max(0, physicalStock - reserved);
    }
}
