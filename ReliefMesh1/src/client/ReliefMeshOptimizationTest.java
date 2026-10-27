
package client;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;


public class ReliefMeshOptimizationTest {

    public static void main(String[] args) throws Exception {
        testShortestRoute();
        testUnreachableDestination();
        testCommitmentPreservation();
        testRiskyOptionRequiresApproval();
        testPartialDelivery();
        testConcurrentRouteReads();

        System.out.println("All optimization tests passed.");
    }

    private static void testShortestRoute() {
        DijkstraRouter router = createRouter();

        DijkstraRouter.RouteResult result =
                router.findRouteResult(
                        "Central Warehouse", "Flood Zone");

        check(result.reachable(),
                "Flood Zone should be reachable.");

        check(result.totalDistance() == 8,
                "Shortest distance should be 8.");

        List<String> expectedPath = List.of(
                "Central Warehouse",
                "Junction B",
                "Junction A",
                "Flood Zone"
        );

        check(result.path().equals(expectedPath),
                "Incorrect shortest path.");

        System.out.println("PASS: shortest route");
    }

    private static void testUnreachableDestination() {
        DijkstraRouter router = createRouter();

        DijkstraRouter.RouteResult result =
                router.findRouteResult(
                        "Central Warehouse", "Unknown Zone");

        check(!result.reachable(),
                "Unknown Zone should be unreachable.");

        check(result.path().isEmpty(),
                "Unreachable path should be empty.");

        System.out.println("PASS: unreachable destination");
    }

    private static void testCommitmentPreservation() {
        CommitmentCoordinator coordinator =
                new CommitmentCoordinator();

        List<CommitmentCoordinator.Option> options = List.of(
                new CommitmentCoordinator.Option(
                        "Central Warehouse", 10, 40, 5, false),

                new CommitmentCoordinator.Option(
                        "North Warehouse", 30, 100, 25, false),

                new CommitmentCoordinator.Option(
                        "South Warehouse", 30, 100, 10, true)
        );

        CommitmentCoordinator.Recommendation result =
                coordinator.chooseOption(25, true, options);

        check(result.getOption() != null,
                "A feasible option should be selected.");

        check(result.getOption().getName().equals("North Warehouse"),
                "North Warehouse should be selected to preserve commitments.");

        check(result.isFullDeliveryPossible(),
                "A full delivery should be possible.");

        check(result.getFeasibleQuantity() == 25,
                "The selected option should deliver 25 units.");

        check(!result.isCommitmentAtRisk(),
                "The selected option should not risk a commitment.");

        check(!result.isApprovalRequired(),
                "Approval should not be required for the safe option.");

        check(result.getExplanation().contains("without risking"),
                "Explanation should state why the safe option was chosen.");

        System.out.println("PASS: commitment preservation");
    }

    private static void testRiskyOptionRequiresApproval() {
        CommitmentCoordinator coordinator =
                new CommitmentCoordinator();

        List<CommitmentCoordinator.Option> options = List.of(
                new CommitmentCoordinator.Option(
                        "South Warehouse", 30, 100, 10, true)
        );

        CommitmentCoordinator.Recommendation result =
                coordinator.chooseOption(25, true, options);

        check(result.getOption() != null,
                "The risky feasible option should be identified.");

        check(result.isCommitmentAtRisk(),
                "The selected option should flag commitment risk.");

        check(result.isApprovalRequired(),
                "A risky option must require human approval.");

        System.out.println("PASS: risky option requires approval");
    }

    private static void testPartialDelivery() {
        CommitmentCoordinator coordinator =
                new CommitmentCoordinator();

        List<CommitmentCoordinator.Option> options = List.of(
                new CommitmentCoordinator.Option(
                        "North Warehouse", 12, 20, 15, false),

                new CommitmentCoordinator.Option(
                        "South Warehouse", 8, 30, 10, false)
        );

        CommitmentCoordinator.Recommendation result =
                coordinator.chooseOption(25, false, options);

        check(!result.isFullDeliveryPossible(),
                "A single option cannot deliver all 25 units.");

        check(result.getFeasibleQuantity() == 12,
                "The largest partial delivery should be 12 units.");

        check(result.getOption().getName().equals("North Warehouse"),
                "The largest safe partial option should be selected.");

        System.out.println("PASS: partial delivery");
    }

    private static void testConcurrentRouteReads() throws Exception {
        DijkstraRouter router = createRouter();

        ExecutorService executor =
                Executors.newFixedThreadPool(8);

        try {
            List<Future<Integer>> results =
                    IntStream.range(0, 100)
                            .mapToObj(i -> executor.submit(() ->
                                    router.findRouteResult(
                                            "Central Warehouse",
                                            "Flood Zone")
                                            .totalDistance()))
                            .toList();

            for (Future<Integer> result : results) {
                check(result.get() == 8,
                        "Concurrent route calculation failed.");
            }
        } finally {
            executor.shutdownNow();
        }

        System.out.println("PASS: concurrent route reads");
    }

    private static DijkstraRouter createRouter() {
        DijkstraRouter router = new DijkstraRouter();

        router.addRoad("Central Warehouse", "Junction A", 4);
        router.addRoad("Central Warehouse", "Junction B", 2);
        router.addRoad("Junction B", "Junction A", 1);
        router.addRoad("Junction A", "Flood Zone", 5);
        router.addRoad("Junction B", "Flood Zone", 8);

        return router;
    }

    private static void check(
            boolean condition, String message) {

        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
