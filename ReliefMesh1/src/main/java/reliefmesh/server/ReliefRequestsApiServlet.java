
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

@WebServlet("/api/relief-requests")
public class ReliefRequestsApiServlet extends HttpServlet {

    private String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
    }

    @Override
    protected void doGet(HttpServletRequest request,
                         HttpServletResponse response)
            throws IOException {

        response.setContentType("application/json;charset=UTF-8");

        String sql =
            "SELECT r.request_id, r.requester_name, r.location, " +
            "r.emergency_type, r.severity, r.people_affected, r.status, " +
            "COALESCE(cr.resource_name, '') AS resource_name, " +
            "COALESCE(cr.required_quantity, 0) AS required_quantity " +
            "FROM relief_requests r " +
            "LEFT JOIN community_requirements cr " +
            "ON cr.request_id = r.request_id " +
            "ORDER BY r.created_at DESC, r.request_id DESC";

        StringBuilder json = new StringBuilder("[");
        boolean first = true;

        try (Connection con = Dbconnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                if (!first) json.append(",");
                first = false;

                json.append("{")
                    .append("\"requestId\":").append(rs.getInt("request_id"))
                    .append(",\"requesterName\":\"")
                    .append(escape(rs.getString("requester_name"))).append("\"")
                    .append(",\"location\":\"")
                    .append(escape(rs.getString("location"))).append("\"")
                    .append(",\"emergencyType\":\"")
                    .append(escape(rs.getString("emergency_type"))).append("\"")
                    .append(",\"severity\":").append(rs.getInt("severity"))
                    .append(",\"peopleAffected\":")
                    .append(rs.getInt("people_affected"))
                    .append(",\"status\":\"")
                    .append(escape(rs.getString("status"))).append("\"")
                    .append(",\"resourceName\":\"")
                    .append(escape(rs.getString("resource_name"))).append("\"")
                    .append(",\"requiredQuantity\":")
                    .append(rs.getInt("required_quantity"))
                    .append("}");
            }

            json.append("]");
            response.getWriter().write(json.toString());

        } catch (SQLException e) {
            response.setStatus(500);
            response.getWriter().write(
                    "{\"error\":\"Could not load relief requests.\"}");
        }
    }
}
