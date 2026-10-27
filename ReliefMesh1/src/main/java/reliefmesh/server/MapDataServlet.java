
package reliefmesh.server;

import reliefmesh.util.Dbconnection;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

@WebServlet("/api/map-data")
public class MapDataServlet extends HttpServlet {

    private String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
    }

    private String text(ResultSet rs, String column)
            throws SQLException {
        return "\"" + escape(rs.getString(column)) + "\"";
    }

    private String number(ResultSet rs, String column)
            throws SQLException {
        Object value = rs.getObject(column);
        return value == null ? "null" : value.toString();
    }

    private String query(Connection con, String sql,
                         RowWriter writer) throws SQLException {
        StringBuilder out = new StringBuilder("[");
        boolean first = true;

        try (PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                if (!first) out.append(",");
                out.append(writer.write(rs));
                first = false;
            }
        }

        return out.append("]").toString();
    }

    @FunctionalInterface
    private interface RowWriter {
        String write(ResultSet rs) throws SQLException;
    }

    @Override
    protected void doGet(HttpServletRequest request,
                         HttpServletResponse response)
            throws IOException {

        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        try (Connection con = Dbconnection.getConnection()) {

            String nodes = query(con,
                "SELECT node_id, location_name, latitude, longitude " +
                "FROM route_nodes ORDER BY node_id",
                rs -> "{\"nodeId\":" + rs.getInt("node_id") +
                      ",\"name\":" + text(rs, "location_name") +
                      ",\"latitude\":" + number(rs, "latitude") +
                      ",\"longitude\":" + number(rs, "longitude") + "}"
            );
            String edges = query(con,
            	    "SELECT edge_id, from_node, to_node, distance_km " +
            	    "FROM route_edges ORDER BY edge_id",
            	    rs -> "{\"edgeId\":" + rs.getInt("edge_id") +
            	          ",\"fromNode\":" + rs.getInt("from_node") +
            	          ",\"toNode\":" + rs.getInt("to_node") +
            	          ",\"distanceKm\":" + rs.getDouble("distance_km") + "}"
            	);

            String warehouses = query(con,
                "SELECT resource_id, resource_name, quantity, storage_location " +
                "FROM resources ORDER BY storage_location, resource_name",
                rs -> "{\"resourceId\":" + rs.getInt("resource_id") +
                      ",\"resourceName\":" + text(rs, "resource_name") +
                      ",\"quantity\":" + rs.getInt("quantity") +
                      ",\"location\":" + text(rs, "storage_location") + "}"
            );

            String requests = query(con,
                "SELECT request_id, requester_name, location, emergency_type, " +
                "severity, people_affected, status " +
                "FROM relief_requests ORDER BY severity DESC, request_id",
                rs -> "{\"requestId\":" + rs.getInt("request_id") +
                      ",\"requesterName\":" + text(rs, "requester_name") +
                      ",\"location\":" + text(rs, "location") +
                      ",\"emergencyType\":" + text(rs, "emergency_type") +
                      ",\"severity\":" + rs.getInt("severity") +
                      ",\"peopleAffected\":" + rs.getInt("people_affected") +
                      ",\"status\":" + text(rs, "status") + "}"
            );

            String vehicles = query(con,
                "SELECT vehicle_id, vehicle_name, capacity, " +
                "current_location, status FROM vehicles ORDER BY vehicle_id",
                rs -> "{\"vehicleId\":" + rs.getInt("vehicle_id") +
                      ",\"vehicleName\":" + text(rs, "vehicle_name") +
                      ",\"capacity\":" + rs.getInt("capacity") +
                      ",\"location\":" + text(rs, "current_location") +
                      ",\"status\":" + text(rs, "status") + "}"
            );

            String deliveries = query(con,
                "SELECT delivery_id, request_id, vehicle_id, resource_name, " +
                "quantity, status, source_warehouse_id " +
                "FROM deliveries ORDER BY delivery_id DESC",
                rs -> "{\"deliveryId\":" + rs.getInt("delivery_id") +
                      ",\"requestId\":" + rs.getInt("request_id") +
                      ",\"vehicleId\":" + rs.getInt("vehicle_id") +
                      ",\"resourceName\":" + text(rs, "resource_name") +
                      ",\"quantity\":" + rs.getInt("quantity") +
                      ",\"status\":" + text(rs, "status") +
                      ",\"sourceWarehouseId\":" +
                      number(rs, "source_warehouse_id") + "}"
            );

            response.getWriter().write(
            	    "{\"nodes\":" + nodes +
            	    ",\"edges\":" + edges +
            	    ",\"warehouses\":" + warehouses +
            	    ",\"requests\":" + requests +
            	    ",\"vehicles\":" + vehicles +
            	    ",\"deliveries\":" + deliveries + "}"
            	);

        } catch (SQLException e) {
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.getWriter().write(
                "{\"error\":\"Could not load map data from the database.\"}"
            );
            getServletContext().log("Map data query failed", e);
        }
    }
}
