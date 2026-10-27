
package reliefmesh.util;

import java.sql.SQLException;

public class RouteDatabaseTest {

    public static void main(String[] args) {

        try {
            RouteOptimizer.RouteResult result =
                    RouteDatabaseService.findRoute(
                            "Central Warehouse",
                            "Hospital"
                    );

            System.out.println("Database route test");
            System.out.println("Path: " + result.path);
            System.out.println("Distance: " + result.distance + " km");

            if (result.path.size() == 3
                    && result.path.get(0) == 1
                    && result.path.get(1) == 3
                    && result.path.get(2) == 4
                    && Math.abs(result.distance - 4.0) < 0.000001) {

                System.out.println(
                        "PASS: Database-backed route optimization works.");
            } else {
                System.out.println(
                        "FAIL: Route differs from the expected shortest path.");
            }

        } catch (SQLException | IllegalArgumentException e) {
            System.out.println("FAIL: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
