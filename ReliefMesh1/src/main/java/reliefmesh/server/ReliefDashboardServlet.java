
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

@WebServlet("/dashboard")
public class ReliefDashboardServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest request,
                         HttpServletResponse response)
            throws ServletException, IOException {

        response.setContentType("text/html;charset=UTF-8");

        try (Connection con = Dbconnection.getConnection()) {

            response.getWriter().println(
                "<!DOCTYPE html><html><head>"
                + "<title>ReliefMesh Dashboard</title>"
                + "<style>"
                + "body{font-family:Arial;margin:30px;background:#f4f6f8}"
                + "table{border-collapse:collapse;width:100%;background:white}"
                + "th,td{padding:10px;border:1px solid #ddd;text-align:left}"
                + "th{background:#17365d;color:white}"
                + ".critical{color:#b00020;font-weight:bold}"
                + "</style></head><body>"
                + "<h1>ReliefMesh - Relief Management</h1>"
                + "<h2>Registered Relief Requests</h2>"
                + "<table><tr>"
                + "<th>ID</th><th>Requester</th><th>Location</th>"
                + "<th>Emergency</th><th>Severity</th>"
                + "<th>People</th><th>Status</th>"
                + "</tr>"
            );

            String sql =
                "SELECT request_id, requester_name, location, "
                + "emergency_type, severity, people_affected, status "
                + "FROM relief_requests "
                + "ORDER BY severity DESC, created_at ASC";

            try (PreparedStatement ps = con.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {

                boolean found = false;

                while (rs.next()) {
                    found = true;

                    int severity = rs.getInt("severity");

                    response.getWriter().println("<tr>");

                    response.getWriter().println(
                        "<td>" + rs.getInt("request_id") + "</td>"
                    );

                    response.getWriter().println(
                        "<td>" + escape(rs.getString("requester_name"))
                        + "</td>"
                    );

                    response.getWriter().println(
                        "<td>" + escape(rs.getString("location")) + "</td>"
                    );

                    response.getWriter().println(
                        "<td>" + escape(rs.getString("emergency_type"))
                        + "</td>"
                    );

                    String severityClass =
                        severity >= 4 ? "critical" : "";

                    response.getWriter().println(
                        "<td class='" + severityClass + "'>"
                        + severity
                        + (severity >= 4 ? " - CRITICAL" : "")
                        + "</td>"
                    );

                    response.getWriter().println(
                        "<td>" + rs.getInt("people_affected") + "</td>"
                    );

                    response.getWriter().println(
                        "<td>" + escape(rs.getString("status")) + "</td>"
                    );

                    response.getWriter().println("</tr>");
                }

                if (!found) {
                    response.getWriter().println(
                        "<tr><td colspan='7'>"
                        + "No relief requests registered yet."
                        + "</td></tr>"
                    );
                }
            }


response.getWriter().println(
    "</table><br>"
    + "<a href='new_request.html'>Register New Relief Request</a>"
    + " | <a href='coordination'>Coordination Engine</a>"
    + " | <a href='shortages'>Shortage Report</a>"
    + " | <a href='deliveries'>Delivery Management</a>"
    + "</body></html>"
);


        } catch (SQLException e) {
            throw new ServletException(
                "Unable to load relief requests from MySQL.", e
            );
        }
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
