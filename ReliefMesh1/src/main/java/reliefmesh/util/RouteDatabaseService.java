
package reliefmesh.util;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public final class RouteDatabaseService {

    public RouteDatabaseService() {
    }

    public static RouteOptimizer.RouteResult findRoute(
            String startLocation, String destinationLocation)
            throws SQLException {

        if (startLocation == null || startLocation.isBlank()
                || destinationLocation == null
                || destinationLocation.isBlank()) {
            throw new IllegalArgumentException(
                    "Start and destination locations are required.");
        }

        RouteOptimizer router = new RouteOptimizer();

        try (Connection connection = Dbconnection.getConnection()) {

            int startId = findNodeId(connection, startLocation);
            int destinationId =
                    findNodeId(connection, destinationLocation);

            String sql =
                    "SELECT from_node, to_node, distance_km "
                    + "FROM route_edges";

            try (PreparedStatement statement =
                         connection.prepareStatement(sql);
                 ResultSet result = statement.executeQuery()) {

                while (result.next()) {
                    int fromId = result.getInt("from_node");
                    int toId = result.getInt("to_node");
                    double distance = result.getDouble("distance_km");

                    if (!Double.isFinite(distance) || distance < 0) {
                        throw new SQLException(
                                "Invalid distance in route_edges.");
                    }

                    router.addRoad(fromId, toId, distance);
                }
            }
            
            return router.findShortestRoute(startId, destinationId);
        }
    }

    private static int findNodeId(
            Connection connection, String location)
            throws SQLException {

        String sql =
                "SELECT node_id FROM route_nodes "
                + "WHERE LOWER(TRIM(location_name)) = LOWER(TRIM(?))";

        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            statement.setString(1, location);

            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    return result.getInt("node_id");
                }
            }
        }

        throw new SQLException(
                "Location not found in route_nodes: " + location);
    }
}
