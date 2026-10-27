
package client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

public final class DijkstraRouter {

    private final Map<String, Map<String, Integer>> graph =
            new HashMap<>();

    public record RouteResult(
            boolean reachable,
            int totalDistance,
            List<String> path) {

        public RouteResult {
            path = List.copyOf(path);
        }
    }

    private record Node(String name, long distance) {}

    public synchronized void addRoad(
            String from, String to, int distance) {

        if (from == null || from.isBlank()
                || to == null || to.isBlank()) {
            throw new IllegalArgumentException(
                    "Locations cannot be empty.");
        }

        if (from.equals(to)) {
            throw new IllegalArgumentException(
                    "A road must connect different locations.");
        }

        if (distance < 0) {
            throw new IllegalArgumentException(
                    "Distance cannot be negative.");
        }

        graph.computeIfAbsent(from, k -> new HashMap<>())
                .put(to, distance);

        graph.computeIfAbsent(to, k -> new HashMap<>())
                .put(from, distance);
    }

    public RouteResult findRouteResult(
            String start, String destination) {

        if (start == null || start.isBlank()
                || destination == null || destination.isBlank()) {
            throw new IllegalArgumentException(
                    "Start and destination are required.");
        }

        Map<String, Map<String, Integer>> snapshot =
                new HashMap<>();

        synchronized (this) {
            for (var entry : graph.entrySet()) {
                snapshot.put(
                        entry.getKey(),
                        new HashMap<>(entry.getValue()));
            }
        }

        if (start.equals(destination)) {
            return new RouteResult(true, 0, List.of(start));
        }

        if (!snapshot.containsKey(start)
                || !snapshot.containsKey(destination)) {
            return unreachable();
        }

        Map<String, Long> distances = new HashMap<>();
        Map<String, String> previous = new HashMap<>();
        Set<String> visited = new HashSet<>();

        distances.put(start, 0L);

        PriorityQueue<Node> queue = new PriorityQueue<>(
                Comparator.comparingLong(Node::distance));

        queue.offer(new Node(start, 0));

        while (!queue.isEmpty()) {
            Node current = queue.poll();

            if (visited.contains(current.name())) {
                continue;
            }

            if (current.distance()
                    != distances.getOrDefault(
                            current.name(), Long.MAX_VALUE)) {
                continue;
            }

            visited.add(current.name());

            if (current.name().equals(destination)) {
                break;
            }

            for (var road : snapshot.getOrDefault(
                    current.name(), Collections.emptyMap()).entrySet()) {

                String next = road.getKey();

                if (visited.contains(next)) {
                    continue;
                }

                long candidate =
                        current.distance() + road.getValue();

                if (candidate < distances.getOrDefault(
                        next, Long.MAX_VALUE)) {

                    distances.put(next, candidate);
                    previous.put(next, current.name());

                    queue.offer(new Node(next, candidate));
                }
            }
        }

        if (!distances.containsKey(destination)) {
            return unreachable();
        }

        long total = distances.get(destination);

        if (total > Integer.MAX_VALUE) {
            throw new ArithmeticException(
                    "Route distance exceeds supported integer range.");
        }

        List<String> path = new ArrayList<>();
        String current = destination;

        while (current != null) {
            path.add(current);

            if (current.equals(start)) {
                break;
            }

            current = previous.get(current);
        }

        if (!path.get(path.size() - 1).equals(start)) {
            return unreachable();
        }

        Collections.reverse(path);

        return new RouteResult(true, (int) total, path);
    }

    public List<String> findRoute(
            String start, String destination) {

        return findRouteResult(start, destination).path();
    }

    private static RouteResult unreachable() {
        return new RouteResult(false, -1, List.of());
    }
}
