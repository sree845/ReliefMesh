
package reliefmesh.server;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public final class RequestStatusService {

    private RequestStatusService() {
    }

    public static void reconcile(Connection con, int requestId)
            throws SQLException {

        if (con == null) {
            throw new IllegalArgumentException(
                    "Database connection is required.");
        }

        String sql =
            "SELECT cr.resource_name, cr.required_quantity, " +
            "COALESCE(SUM(CASE WHEN d.status = 'DELIVERED' " +
            "THEN d.quantity ELSE 0 END), 0) AS delivered " +
            "FROM community_requirements cr " +
            "LEFT JOIN deliveries d " +
            "ON d.request_id = cr.request_id " +
            "AND d.resource_name = cr.resource_name " +
            "WHERE cr.request_id = ? " +
            "GROUP BY cr.requirement_id, cr.resource_name, " +
            "cr.required_quantity";

        boolean hasRequirement = false;
        boolean fulfilled = true;
        boolean partiallyFulfilled = false;
        boolean overDelivered = false;

        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, requestId);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    hasRequirement = true;

                    int required = rs.getInt("required_quantity");
                    int delivered = rs.getInt("delivered");

                    if (required < 0 || delivered < 0) {
                        throw new SQLException(
                                "Invalid requirement or delivery quantity.");
                    }

                    if (delivered > required) {
                        overDelivered = true;
                    }

                    if (delivered < required) {
                        fulfilled = false;
                    }

                    if (delivered > 0) {
                        partiallyFulfilled = true;
                    }
                }
            }
        }

        String status;

        if (!hasRequirement) {
            status = "PENDING";
        } else if (overDelivered) {
            status = "REVIEW_REQUIRED";
        } else if (fulfilled) {
            status = "FULFILLED";
        } else if (partiallyFulfilled) {
            status = "PARTIALLY_FULFILLED";
        } else {
            status = "PENDING";
        }

        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE relief_requests SET status = ? " +
                "WHERE request_id = ?")) {
            ps.setString(1, status);
            ps.setInt(2, requestId);

            if (ps.executeUpdate() != 1) {
                throw new SQLException(
                        "Relief request not found: " + requestId);
            }
        }
    }
}
