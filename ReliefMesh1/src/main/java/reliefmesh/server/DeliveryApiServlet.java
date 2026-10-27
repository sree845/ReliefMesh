
package reliefmesh.server;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import reliefmesh.util.Dbconnection;

@WebServlet("/api/deliveries")
public class DeliveryApiServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet(HttpServletRequest request,
                         HttpServletResponse response)
            throws IOException {

        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");

        String sql =
            "SELECT delivery_id, request_id, resource_name, " +
            "quantity, status, created_at " +
            "FROM deliveries ORDER BY delivery_id DESC";

        StringBuilder json = new StringBuilder("[");
        boolean first = true;

        try (Connection con = Dbconnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                if (!first) {
                    json.append(",");
                }
                first = false;

                Timestamp created = rs.getTimestamp("created_at");

                json.append("{")
                    .append("\"deliveryId\":")
                    .append(rs.getInt("delivery_id"))
                    .append(",\"requestId\":")
                    .append(rs.getInt("request_id"))
                    .append(",\"resourceName\":\"")
                    .append(escape(rs.getString("resource_name")))
                    .append("\",\"quantity\":")
                    .append(rs.getInt("quantity"))
                    .append(",\"status\":\"")
                    .append(escape(rs.getString("status")))
                    .append("\",\"createdAt\":\"")
                    .append(created == null ? "" : created.toString())
                    .append("\"}");
            }

            json.append("]");
            response.getWriter().write(json.toString());

        } catch (Exception e) {
            response.setStatus(
                HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.getWriter().write(
                "{\"error\":\"Unable to load deliveries\"}");
            getServletContext().log("Delivery API failed", e);
        }
    }

    private String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r");
    }
}
