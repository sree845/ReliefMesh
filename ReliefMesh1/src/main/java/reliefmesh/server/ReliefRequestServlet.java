package reliefmesh.server;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import reliefmesh.util.Dbconnection;

@WebServlet("/relief-request")
public class ReliefRequestServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    private static final Set<String> ALLOWED_EMERGENCIES = Set.of(
        "Flood",
        "Fire",
        "Medical Emergency",
        "Earthquake",
        "Other"
    );

    @Override
    protected void doPost(
            HttpServletRequest request,
            HttpServletResponse response)
            throws ServletException, IOException {

        request.setCharacterEncoding("UTF-8");
        response.setContentType("text/html;charset=UTF-8");

        // Step 1: Read form data.
        String requesterName = trim(request.getParameter("requesterName"));
        String location = trim(request.getParameter("location"));
        String emergencyType = trim(request.getParameter("emergencyType"));
        String resourceName = trim(request.getParameter("resourceName"));

        int severity;
        int peopleAffected;
        int requiredQuantity;

        // Step 2: Validate numeric inputs.
        try {
            severity = Integer.parseInt(request.getParameter("severity"));
            peopleAffected =
                Integer.parseInt(request.getParameter("peopleAffected"));
            requiredQuantity =
                Integer.parseInt(request.getParameter("requiredQuantity"));

        } catch (NumberFormatException | NullPointerException e) {
            response.sendError(
                HttpServletResponse.SC_BAD_REQUEST,
                "Severity, people affected and required quantity must be valid numbers."
            );
            return;
        }

        // Step 3: Validate all request fields.
        if (requesterName == null
                || requesterName.isBlank()
                || requesterName.length() > 100

                || location == null
                || location.isBlank()
                || location.length() > 255

                || emergencyType == null
                || !ALLOWED_EMERGENCIES.contains(emergencyType)

                || severity < 1
                || severity > 5

                || peopleAffected < 1
                || peopleAffected > 10_000_000

                || resourceName == null
                || resourceName.isBlank()
                || resourceName.length() > 100

                || requiredQuantity < 1
                || requiredQuantity > 1_000_000) {

            response.sendError(
                HttpServletResponse.SC_BAD_REQUEST,
                "Invalid form data. Check all fields and quantity limits."
            );
            return;
        }

        // Step 4: Save the request and its requirement atomically.
        try (Connection con = Dbconnection.getConnection()) {

            con.setAutoCommit(false);

            try {
                // Verify that the selected resource exists.
                String resourceSql =
                    "SELECT resource_id FROM resources "
                    + "WHERE resource_name = ? LIMIT 1";

                try (PreparedStatement ps =
                        con.prepareStatement(resourceSql)) {

                    ps.setString(1, resourceName);

                    try (ResultSet rs = ps.executeQuery()) {

                        if (!rs.next()) {
                            con.rollback();

                            response.sendError(
                                HttpServletResponse.SC_BAD_REQUEST,
                                "The selected resource does not exist in inventory."
                            );
                            return;
                        }
                    }
                }

                // Insert the emergency request.
                String requestSql =
                    "INSERT INTO relief_requests "
                    + "(requester_name, location, emergency_type, "
                    + "severity, people_affected, status) "
                    + "VALUES (?, ?, ?, ?, ?, 'PENDING')";

                int requestId;

                try (PreparedStatement ps = con.prepareStatement(
                        requestSql,
                        Statement.RETURN_GENERATED_KEYS)) {

                    ps.setString(1, requesterName);
                    ps.setString(2, location);
                    ps.setString(3, emergencyType);
                    ps.setInt(4, severity);
                    ps.setInt(5, peopleAffected);

                    int rowsInserted = ps.executeUpdate();

                    if (rowsInserted != 1) {
                        throw new SQLException(
                            "The relief request could not be inserted."
                        );
                    }

                    try (ResultSet keys = ps.getGeneratedKeys()) {

                        if (!keys.next()) {
                            throw new SQLException(
                                "The database did not return a request ID."
                            );
                        }

                        requestId = keys.getInt(1);
                    }
                }

                // Insert the resource requirement for this request.
                String requirementSql =
                    "INSERT INTO community_requirements "
                    + "(request_id, resource_name, required_quantity) "
                    + "VALUES (?, ?, ?)";

                try (PreparedStatement ps =
                        con.prepareStatement(requirementSql)) {

                    ps.setInt(1, requestId);
                    ps.setString(2, resourceName);
                    ps.setInt(3, requiredQuantity);

                    int rowsInserted = ps.executeUpdate();

                    if (rowsInserted != 1) {
                        throw new SQLException(
                            "The resource requirement could not be inserted."
                        );
                    }
                }

                // Both database inserts succeeded.
                con.commit();

                // Step 5: Display confirmation.
                response.setStatus(
                    HttpServletResponse.SC_CREATED
                );

                response.getWriter().println(
                    "<!DOCTYPE html>"
                    + "<html lang='en'>"
                    + "<head>"
                    + "<meta charset='UTF-8'>"
                    + "<title>ReliefMesh - Request Registered</title>"
                    + "</head>"
                    + "<body>"
                    + "<h1>ReliefMesh</h1>"
                    + "<h2>Relief Request Registered Successfully</h2>"
                    + "<p><b>Request ID:</b> " + requestId + "</p>"
                    + "<p><b>Requester:</b> "
                    + escapeHtml(requesterName) + "</p>"
                    + "<p><b>Location:</b> "
                    + escapeHtml(location) + "</p>"
                    + "<p><b>Emergency:</b> "
                    + escapeHtml(emergencyType) + "</p>"
                    + "<p><b>Severity:</b> " + severity + "</p>"
                    + "<p><b>People affected:</b> "
                    + peopleAffected + "</p>"
                    + "<p><b>Required resource:</b> "
                    + escapeHtml(resourceName) + "</p>"
                    + "<p><b>Required quantity:</b> "
                    + requiredQuantity + " units</p>"
                    + "<p><b>Status:</b> PENDING</p>"
                    + "<p>The request and requirement have been saved. "
                    + "Inventory has not been reserved or deducted.</p>"
                    + "<p><a href='"
                    + request.getContextPath()
                    + "/new_request.html'>Register another request</a></p>"
                    + "<p><a href='"
                    + request.getContextPath()
                    + "/dashboard'>Dashboard</a></p>"
                    + "<p><a href='"
                    + request.getContextPath()
                    + "/coordination'>Coordination</a></p>"
                    + "</body></html>"
                );

            } catch (SQLException | RuntimeException e) {

                // Never leave only one of the two inserts committed.
                try {
                    con.rollback();
                } catch (SQLException rollbackError) {
                    e.addSuppressed(rollbackError);
                }

                throw e;
            }

        } catch (SQLException e) {

            getServletContext().log(
                "Failed to register relief request",
                e
            );

            throw new ServletException(
                "Could not save the relief request. "
                + "Check the server log for details.",
                e
            );
        }
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static String escapeHtml(String value) {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }
}