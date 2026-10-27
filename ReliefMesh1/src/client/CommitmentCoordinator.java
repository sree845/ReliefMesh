
package client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Evaluates relief-delivery options while protecting
 * existing delivery commitments.
 *
 * This class recommends options only. It never changes
 * database records or cancels active deliveries.
 */
public final class CommitmentCoordinator {

    public static final class Option {

        private final String name;
        private final int availableStock;
        private final int vehicleCapacity;
        private final int travelMinutes;
        private final boolean commitmentRisk;

        // Backwards-compatible constructor.
        public Option(
                String name,
                int availableStock,
                int vehicleCapacity,
                int travelMinutes) {

            this(name, availableStock, vehicleCapacity,
                    travelMinutes, false);
        }

        public Option(
                String name,
                int availableStock,
                int vehicleCapacity,
                int travelMinutes,
                boolean commitmentRisk) {

            if (name == null || name.trim().isEmpty()) {
                throw new IllegalArgumentException(
                        "Option name is required.");
            }

            if (availableStock < 0
                    || vehicleCapacity < 0
                    || travelMinutes < 0) {
                throw new IllegalArgumentException(
                        "Stock, capacity and travel time "
                        + "must be non-negative.");
            }

            this.name = name;
            this.availableStock = availableStock;
            this.vehicleCapacity = vehicleCapacity;
            this.travelMinutes = travelMinutes;
            this.commitmentRisk = commitmentRisk;
        }

        public String getName() {
            return name;
        }

        public int getAvailableStock() {
            return availableStock;
        }

        public int getVehicleCapacity() {
            return vehicleCapacity;
        }

        public int getTravelMinutes() {
            return travelMinutes;
        }

        public boolean hasCommitmentRisk() {
            return commitmentRisk;
        }

        public boolean canDeliver(int quantity) {
            return quantity > 0
                    && availableStock >= quantity
                    && vehicleCapacity >= quantity;
        }

        public int getDeliverableQuantity(int requestedQuantity) {
            if (requestedQuantity <= 0) {
                return 0;
            }

            return Math.min(
                    requestedQuantity,
                    Math.min(availableStock, vehicleCapacity));
        }
    }

    public static final class Recommendation {

        private final Option option;
        private final int requestedQuantity;
        private final int feasibleQuantity;
        private final boolean fullDeliveryPossible;
        private final boolean commitmentAtRisk;
        private final boolean approvalRequired;
        private final String explanation;

        private Recommendation(
                Option option,
                int requestedQuantity,
                int feasibleQuantity,
                boolean fullDeliveryPossible,
                boolean commitmentAtRisk,
                boolean approvalRequired,
                String explanation) {

            this.option = option;
            this.requestedQuantity = requestedQuantity;
            this.feasibleQuantity = feasibleQuantity;
            this.fullDeliveryPossible = fullDeliveryPossible;
            this.commitmentAtRisk = commitmentAtRisk;
            this.approvalRequired = approvalRequired;
            this.explanation = explanation;
        }

        public Option getOption() {
            return option;
        }

        public int getRequestedQuantity() {
            return requestedQuantity;
        }

        public int getFeasibleQuantity() {
            return feasibleQuantity;
        }

        public boolean isFullDeliveryPossible() {
            return fullDeliveryPossible;
        }

        public boolean isCommitmentAtRisk() {
            return commitmentAtRisk;
        }

        public boolean isApprovalRequired() {
            return approvalRequired;
        }

        public String getExplanation() {
            return explanation;
        }
    }

    /**
     * Selects a feasible option.
     *
     * Safe options are preferred over risky options.
     * Travel time breaks ties between equally safe options.
     *
     * existingCommitmentAtRisk indicates that the current
     * situation involves an existing delivery commitment.
     */
    public Recommendation chooseOption(
            int requiredQuantity,
            boolean existingCommitmentAtRisk,
            List<Option> options) {

        if (requiredQuantity <= 0) {
            throw new IllegalArgumentException(
                    "Required quantity must be greater than zero.");
        }

        if (options == null) {
            throw new IllegalArgumentException(
                    "Options list cannot be null.");
        }

        List<Option> fullOptions = new ArrayList<>();

        for (Option option : options) {
            if (option != null
                    && option.canDeliver(requiredQuantity)) {
                fullOptions.add(option);
            }
        }

        Comparator<Option> priority =
                Comparator.comparing(Option::hasCommitmentRisk)
                        .thenComparingInt(Option::getTravelMinutes)
                        .thenComparing(Option::getName);

        fullOptions.sort(priority);

        // Prefer a full delivery that does not risk commitments.
        if (!fullOptions.isEmpty()) {
            Option selected = fullOptions.get(0);
            boolean risk = selected.hasCommitmentRisk();
            boolean approvalRequired = risk;

            String explanation;

            if (risk) {
                explanation =
                        "Option " + selected.getName()
                        + " can deliver all " + requiredQuantity
                        + " units, but selecting it risks an existing "
                        + "commitment. Check all alternatives and obtain "
                        + "human approval before proceeding.";
            } else if (existingCommitmentAtRisk) {
                explanation =
                        "Selected " + selected.getName()
                        + " because it can deliver all "
                        + requiredQuantity
                        + " units without risking an existing "
                        + "commitment. This is the preferred safe "
                        + "alternative. Verify route and vehicle "
                        + "availability before assignment.";
            } else {
                explanation =
                        "Selected " + selected.getName()
                        + " because it can deliver all "
                        + requiredQuantity
                        + " units. Verify route and vehicle "
                        + "availability before assignment.";
            }

            return new Recommendation(
                    selected,
                    requiredQuantity,
                    requiredQuantity,
                    true,
                    risk,
                    approvalRequired,
                    explanation);
        }

        // No option can satisfy the full request.
        // Prefer safe options, then the largest feasible quantity.
        List<Option> partialOptions = new ArrayList<>();

        for (Option option : options) {
            if (option != null
                    && option.getDeliverableQuantity(requiredQuantity) > 0) {
                partialOptions.add(option);
            }
        }

        partialOptions.sort(
                Comparator.comparing(Option::hasCommitmentRisk)
                        .thenComparing(
                                Comparator.comparingInt(
                                        (Option o) ->
                                                o.getDeliverableQuantity(
                                                        requiredQuantity))
                                        .reversed())
                        .thenComparingInt(Option::getTravelMinutes)
                        .thenComparing(Option::getName));

        if (!partialOptions.isEmpty()) {
            Option selected = partialOptions.get(0);
            int quantity =
                    selected.getDeliverableQuantity(requiredQuantity);
            boolean risk = selected.hasCommitmentRisk();

            String explanation =
                    "No single option can deliver all "
                    + requiredQuantity + " units. "
                    + selected.getName() + " can deliver "
                    + quantity + " units, leaving "
                    + (requiredQuantity - quantity)
                    + " units unmet. Check other warehouses and vehicles "
                    + "before considering disruption of an active "
                    + "commitment.";

            if (risk) {
                explanation +=
                        " This option risks an existing commitment; "
                        + "human approval is required.";
            }

            return new Recommendation(
                    selected,
                    requiredQuantity,
                    quantity,
                    false,
                    risk,
                    risk,
                    explanation);
        }

        return new Recommendation(
                null,
                requiredQuantity,
                0,
                false,
                false,
                false,
                "No option has positive feasible capacity. Check "
                + "inventory, vehicles, routes and active commitments. "
                + "Human review is required before any intervention.");
    }

    /**
     * Backwards-compatible helper returning only the explanation.
     */
    public String chooseOption(
            int requiredQuantity,
            boolean existingCommitmentAtRisk,
            List<Option> options,
            boolean explanationOnly) {

        return chooseOption(
                requiredQuantity,
                existingCommitmentAtRisk,
                options).getExplanation();
    }

    /**
     * Returns full-delivery options, with safe options first.
     */
    public List<Option> getFeasibleOptions(
            int requiredQuantity,
            List<Option> options) {

        if (requiredQuantity <= 0 || options == null) {
            return Collections.emptyList();
        }

        List<Option> result = new ArrayList<>();

        for (Option option : options) {
            if (option != null && option.canDeliver(requiredQuantity)) {
                result.add(option);
            }
        }

        result.sort(
                Comparator.comparing(Option::hasCommitmentRisk)
                        .thenComparingInt(Option::getTravelMinutes)
                        .thenComparing(Option::getName));

        return Collections.unmodifiableList(result);
    }
}
