
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

@WebServlet("/shortages")
public class ShortageServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest request,
                         HttpServletResponse response)
            throws ServletException, IOException {

        response.setContentType("text/html;charset=UTF-8");

        String sql =
            "SELECT d.resource_name, d.required_quantity, "
            + "COALESCE(s.available_quantity, 0) AS available_quantity "
            + "FROM ("
            + " SELECT resource_name, "
            + " SUM(required_quantity) AS required_quantity "
            + " FROM community_requirements "
            + " GROUP BY resource_name"
            + ") d "
            + "LEFT JOIN ("
            + " SELECT resource_name, SUM(quantity) AS available_quantity "
            + " FROM resources "
            + " GROUP BY resource_name"
            + ") s ON d.resource_name = s.resource_name "
            + "ORDER BY d.resource_name";

        response.getWriter().println(
            "<!DOCTYPE html><html><head>"
            + "<title>ReliefMesh Shortage Report</title>"
            + "<style>"
            + "body{font-family:Arial;margin:30px;background:#f4f6f8}"
            + "table{border-collapse:collapse;width:100%;background:white}"
            + "th,td{padding:12px;border:1px solid #ddd;text-align:left}"
            + "th{background:#17365d;color:white}"
            + ".shortage{color:#b00020;font-weight:bold}"
            + ".ok{color:#18733c;font-weight:bold}"
            + "</style></head><body>"
            + "<h1>ReliefMesh - Shortage Detection</h1>"
            + "<p>Demand is compared with total available inventory.</p>"
            + "<table><tr>"
            + "<th>Resource</th><th>Required</th>"
            + "<th>Available</th><th>Shortage</th><th>Result</th>"
            + "</tr>"
        );

        try (Connection con = Dbconnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            boolean found = false;

            while (rs.next()) {
                found = true;

                String resource = rs.getString("resource_name");
                int required = rs.getInt("required_quantity");
                int available = rs.getInt("available_quantity");

                int shortage = Math.max(0, required - available);

                response.getWriter().println("<tr>");

                response.getWriter().println(
                    "<td>" + escape(resource) + "</td>"
                );

                response.getWriter().println(
                    "<td>" + required + "</td>"
                );

                response.getWriter().println(
                    "<td>" + available + "</td>"
                );

                response.getWriter().println(
                    "<td>" + shortage + "</td>"
                );

                if (shortage > 0) {
                    response.getWriter().println(
                        "<td class='shortage'>SHORTAGE</td>"
                    );
                } else {
                    response.getWriter().println(
                        "<td class='ok'>SUFFICIENT</td>"
                    );
                }

                response.getWriter().println("</tr>");
            }

            if (!found) {
                response.getWriter().println(
                    "<tr><td colspan='5'>"
                    + "No resource requirements registered yet."
                    + "</td></tr>"
                );
            }

        } catch (SQLException e) {
            throw new ServletException(
                "Unable to calculate resource shortages.", e
            );
        }

        response.getWriter().println(
            "</table><br>"
            + "<a href='" + request.getContextPath()
            + "/dashboard'>View Relief Requests</a>"
            + "</body></html>"
        );
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
