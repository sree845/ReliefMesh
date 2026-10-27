
package reliefmesh.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

public class RouteOptimizer {

    private static class Edge {
        private final int destination;
        private final double distance;

        Edge(int destination, double distance) {
            this.destination = destination;
            this.distance = distance;
        }
    }

    private static class Node {
        private final int id;
        private final double distance;

        Node(int id, double distance) {
            this.id = id;
            this.distance = distance;
        }
    }

    public static class RouteResult {
        public final List<Integer> path;
        public final double distance;

        public RouteResult(List<Integer> path, double distance) {
            this.path = Collections.unmodifiableList(
                    new ArrayList<>(path));
            this.distance = distance;
        }

        public boolean isReachable() {
            return Double.isFinite(distance);
        }
    }

    private final Map<Integer, List<Edge>> graph = new HashMap<>();

    public void addRoad(int from, int to, double distance) {
        if (!Double.isFinite(distance) || distance < 0) {
            throw new IllegalArgumentException(
                    "Distance must be finite and non-negative.");
        }

        graph.computeIfAbsent(from, key -> new ArrayList<>())
             .add(new Edge(to, distance));

        graph.computeIfAbsent(to, key -> new ArrayList<>())
             .add(new Edge(from, distance));
    }

    public RouteResult findShortestRoute(int start, int end) {
        if (start == end) {
            return new RouteResult(
                    Collections.singletonList(start), 0.0);
        }

        Map<Integer, Double> distances = new HashMap<>();
        Map<Integer, Integer> previous = new HashMap<>();

        PriorityQueue<Node> queue = new PriorityQueue<>(
                Comparator.comparingDouble(node -> node.distance));

        distances.put(start, 0.0);
        queue.add(new Node(start, 0.0));

        while (!queue.isEmpty()) {
            Node current = queue.poll();

            double bestDistance = distances.getOrDefault(
                    current.id, Double.POSITIVE_INFINITY);

            if (current.distance > bestDistance) {
                continue;
            }

            if (current.id == end) {
                break;
            }

            for (Edge edge : graph.getOrDefault(
                    current.id, Collections.emptyList())) {

                double candidate =
                        current.distance + edge.distance;

                double knownDistance = distances.getOrDefault(
                        edge.destination, Double.POSITIVE_INFINITY);

                if (candidate < knownDistance) {
                    distances.put(edge.destination, candidate);
                    previous.put(edge.destination, current.id);

                    queue.add(new Node(edge.destination, candidate));
                }
            }
        }

        if (!distances.containsKey(end)) {
            return new RouteResult(
                    Collections.emptyList(),
                    Double.POSITIVE_INFINITY);
        }

        List<Integer> path = new ArrayList<>();
        int current = end;

        while (current != start) {
            path.add(current);

            Integer predecessor = previous.get(current);

            if (predecessor == null) {
                return new RouteResult(
                        Collections.emptyList(),
                        Double.POSITIVE_INFINITY);
            }

            current = predecessor;
        }

        path.add(start);
        Collections.reverse(path);

        return new RouteResult(path, distances.get(end));
    }
}
