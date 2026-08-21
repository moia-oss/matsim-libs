package org.matsim.contrib.drt.extension.flexibleTransit;

import com.google.common.base.Verify;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.IdMap;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.drt.extension.flexibleTransit.VehicleSelection.FlexibleTransitVehicleSelectionStrategy;
import org.matsim.contrib.drt.optimizer.VehicleEntry;
import org.matsim.contrib.drt.passenger.AcceptedDrtRequest;
import org.matsim.contrib.drt.schedule.*;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.contrib.dvrp.fleet.Fleet;
import org.matsim.contrib.dvrp.path.VrpPathWithTravelData;
import org.matsim.contrib.dvrp.path.VrpPaths;
import org.matsim.contrib.dvrp.schedule.CapacityChangeTask;
import org.matsim.contrib.dvrp.schedule.Schedule;
import org.matsim.contrib.dvrp.schedule.Schedules;
import org.matsim.contrib.dvrp.schedule.Task;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.gbl.Gbl;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.router.util.TravelTime;
import org.matsim.pt.transitSchedule.api.*;

import java.util.*;
import java.util.concurrent.ForkJoinPool;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

public class LineServiceManager {

    private final SortedSet<LineServiceChain> services;
    private final DrtTaskFactory taskFactory;

    private final Network network;
    private final TravelTime travelTime;
    private final LeastCostPathCalculator router;

    private final VehicleEntry.EntryFactory vehicleEntryFactory;
    private final ForkJoinPool forkJoinPool;
    private final Fleet fleet;
    private final EventsManager eventsManager;
    private final Map<Id<DvrpVehicle>, Queue<LineService>> activeLineServiceVehicles = new IdMap<>(DvrpVehicle.class);

    //TODO solve this via injection
    private final FlexibleTransitVehicleSelectionStrategy vehicleSelectionStrategy;

    public LineServiceManager(TransitSchedule transitSchedule, FlexibleTransitVehicleSelectionStrategy insertionStrategy, Network network, TravelTime travelTime, LeastCostPathCalculator router, ForkJoinPool forkJoinPool, VehicleEntry.EntryFactory vehicleEntryFactory, Fleet fleet, EventsManager eventsManager, DrtTaskFactory taskFactory) {
        this.taskFactory = taskFactory;
        this.network = network;
        this.travelTime = travelTime;
        this.router = router;
        this.forkJoinPool = forkJoinPool;
        this.vehicleEntryFactory = vehicleEntryFactory;
        this.fleet = fleet;
        this.eventsManager = eventsManager;
        this.services = new TreeSet<>(
                Comparator
                        .comparingDouble((ToDoubleFunction<LineServiceChain>) chain -> chain.service().getDeparture().getDepartureTime())
                        .thenComparing(chain -> chain.service().getRoute().getId())
        );

        Set<Id<Departure>> skippedChainedDepartures = new HashSet<>();

        for (TransitLine transitLine : transitSchedule.getTransitLines().values()) {
            for (TransitRoute transitRoute : transitLine.getRoutes().values()) {
                List<Departure> departures = transitRoute.getDepartures().values().stream()
                        .sorted(Comparator.comparingDouble(Departure::getDepartureTime))
                        .toList();
                for (Departure departure : departures) {
                    if(skippedChainedDepartures.contains(departure.getId())) {
                        continue;
                    }
                    LineService rootService =
                            new LineService(transitLine, transitRoute, departure);

                    List<LineService> chainedServices = new ArrayList<>();

                    processChain(
                            transitLine,
                            transitRoute,
                            departure,
                            skippedChainedDepartures,
                            chainedServices);

                    services.add(new LineServiceChain(rootService, chainedServices));
                }
            }
        }
        vehicleSelectionStrategy = insertionStrategy;
    }

    private static void processChain(
            TransitLine transitLine,
            TransitRoute transitRoute,
            Departure departure,
            Set<Id<Departure>> skippedChainedDepartures,
            List<LineService> chainedServices) {

        for (ChainedDeparture chainedDeparture : departure.getChainedDepartures()) {

            skippedChainedDepartures.add(
                    chainedDeparture.getChainedDepartureId());

            Departure followingDeparture =
                    transitRoute.getDepartures()
                            .get(chainedDeparture.getChainedDepartureId());

            LineService chainedService =
                    new LineService(
                            transitLine,
                            transitRoute,
                            followingDeparture);

            chainedServices.add(chainedService);

            processChain(
                    transitLine,
                    transitRoute,
                    followingDeparture,
                    skippedChainedDepartures,
                    chainedServices);
        }
    }

    public void doSimStep(double now) {

        List<LineServiceChain> schedulableServiceChains = new ArrayList<>();
        for (LineServiceChain lineServiceChain : services) {
            if (lineServiceChain.service().getDeparture().getDepartureTime() > now + 1800) {
                break;
            }
            if(lineServiceChain.service().getDeparture().getDepartureTime() == now + 1800) {
                schedulableServiceChains.add(lineServiceChain);
            }
        }


        if (!schedulableServiceChains.isEmpty()) {
            var vehicleEntries = forkJoinPool.submit(() -> fleet.getVehicles()
                    .values()
                    .parallelStream()
                    .map(v -> vehicleEntryFactory.create(v, now))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toMap(e -> e.vehicle.getId(), e -> e))).join();

            Iterator<LineServiceChain> chainIterator = schedulableServiceChains.iterator();

            while(chainIterator.hasNext()) {

                LineServiceChain chain = chainIterator.next();

                Optional<FlexibleTransitVehicleSelectionStrategy.LineServiceInsertion> selected = vehicleSelectionStrategy.selectVehicle(now, vehicleEntries, chain);
                if(selected.isPresent()) {
                    scheduleLineServiceChain(chain, selected.get(), now);
                    vehicleEntries.remove(selected.get().vehicleEntry().vehicle.getId());
                    VehicleEntry updated = vehicleEntryFactory.create(selected.get().vehicleEntry().vehicle, now);
                    if(updated != null) {
                        vehicleEntries.put(selected.get().vehicleEntry().vehicle.getId(), updated);
                    }
                    chainIterator.remove();
                } else {
                    // TODO throw analysis event instead of hard RTE
                    throw new RuntimeException("could not find a vehicle to schedule line service chain with first departure "
                            + chain.service().getDeparture().getId());
                }
            }
        }
    }

    private void scheduleLineServiceChain(
            LineServiceChain chain,
            FlexibleTransitVehicleSelectionStrategy.LineServiceInsertion selection,
            double now) {


        VehicleEntry vehicleEntry = selection.vehicleEntry();
        int insertionIndex = selection.insertionIndex();

        Schedule schedule = vehicleEntry.vehicle.getSchedule();

        /*
         * Remember the existing stops after the insertion point.
         * These stops will be re-added after the LineService.
         */
        List<DrtStopTask> remainingStops = new ArrayList<>();

        for (int i = insertionIndex; i < vehicleEntry.stops.size(); i++) {
            DrtStopTask task = vehicleEntry.stops.get(i).getTask();

            remainingStops.add(task);
        }

        /*
         * ================================================================
         * Remove the part of the schedule from the insertion point onward
         * and determine the link/time from which we start.
         * ================================================================
         */

        final Link fromLink;
        final double fromTime;

        if (insertionIndex == 0) {

            if (vehicleEntry.stops.isEmpty()) {
                /*
                 * The vehicle has no stops. This is the old behaviour:
                 * truncate the current stay and start from now.
                 */
                DrtStayTask currentTask =
                        (DrtStayTask) schedule.getCurrentTask();

                while (currentTask != Schedules.getLastTask(schedule)) {
                    schedule.removeLastTask();
                }

                currentTask.setEndTime(now);

                fromLink = currentTask.getLink();
                fromTime = now;

            } else {
                /*
                 * The LineService is inserted before the first stop.
                 *
                 * Keep everything up to the task before the first stop.
                 */
                DrtStopTask firstStop =
                        vehicleEntry.stops.getFirst().getTask();

                while (schedule.getTasks().size() > firstStop.getTaskIdx()) {
                    schedule.removeLastTask();
                }

                Task currentTask = schedule.getCurrentTask();

                if (currentTask instanceof DrtStayTask stayTask) {
                    stayTask.setEndTime(now);

                } else if (currentTask instanceof DrtDriveTask) {
                    /*
                     * The current drive has already been represented by vehicleEntry.start
                     * at its diversion point.
                     */
                    //TODO test
                    schedule.removeLastTask();

                } else {
                    throw new IllegalStateException(
                            "Unexpected current task for insertion before first stop: "
                                    + currentTask);
                }

                fromLink = vehicleEntry.start.getLink();
                fromTime = vehicleEntry.start.getDepartureTime();

            }

        } else {

            /*
             * The LineService is inserted after an existing stop.
             */
            DrtStopTask previousStop =
                    vehicleEntry.stops.get(insertionIndex - 1).getTask();

            fromLink = previousStop.getLink();
            fromTime = previousStop.getEndTime();

            /*
             * Remove everything after the previous stop.
             *
             * This also removes the StayTask that used to lead to the
             * next stop.
             */
            while (schedule.getTasks().size()
                    > previousStop.getTaskIdx() + 1) {

                schedule.removeLastTask();
            }
        }


        Link currentLink = fromLink;
        double currentTime = fromTime;

        DrtStopTask lastStopTask = null;

        for (LineService service : chain.allServices()) {

            lastStopTask = addLineService(
                    vehicleEntry.vehicle,
                    schedule,
                    service,
                    currentLink,
                    currentTime);

            currentLink = lastStopTask.getLink();
            currentTime = lastStopTask.getEndTime();
        }

        // EIN finaler Return Drive
        // EIN finaler StayTask

        /*
         * ================================================================
         * Drive from last LineService to the next existing stop
         * ================================================================
         */

        if (!remainingStops.isEmpty()) {

            DrtStopTask oldStop = remainingStops.getFirst();

            double newBeginTime = addDriveAndWaitIfNecessary( vehicleEntry.vehicle,
                    currentLink,
                    currentTime,
                    oldStop);

            double stopDuration =
                    oldStop.getEndTime() - oldStop.getBeginTime();

            /*
             * Recreate the delegate so that the old requests are retained.
             */
            DrtStopTask recreated = recreateStopTask(
                    vehicleEntry.vehicle,
                    oldStop,
                    newBeginTime,
                    newBeginTime+stopDuration);

            schedule.addTask(recreated);

            lastStopTask = recreated;

            /*
             * ============================================================
             * Rebuild the remaining existing stops
             * ============================================================
             */

            for (int i = 1; i < remainingStops.size(); i++) {

                DrtStopTask oldNextStop = remainingStops.get(i);

                double beginTime = addDriveAndWaitIfNecessary( vehicleEntry.vehicle,
                        lastStopTask.getLink(),
                        lastStopTask.getEndTime(),
                        oldNextStop);

                double oldStopDuration = oldNextStop.getEndTime() - oldNextStop.getBeginTime();

                /*
                 * Recreate the delegate so that the old requests are retained.
                 */
                DrtStopTask recreatedNextStop = recreateStopTask(
                        vehicleEntry.vehicle,
                        oldNextStop,
                        beginTime,
                        beginTime + oldStopDuration);

                schedule.addTask(recreatedNextStop);

                lastStopTask = recreatedNextStop;

            }
        }

        /*
         * ================================================================
         * Final stay
         * ================================================================
         */

        schedule.addTask(
                taskFactory.createStayTask(
                        vehicleEntry.vehicle,
                        lastStopTask.getEndTime(),
                        vehicleEntry.vehicle.getServiceEndTime(),
                        lastStopTask.getLink()));

        /*
         * ================================================================
         * Event
         * ================================================================
         */
//		//TODO mode is hardcoded
        eventsManager.processEvent(
                new LineServiceScheduledEvent(
                        now,
                        "drt",
                        vehicleEntry.vehicle.getId(),
                        chain.service().getLine().getId(),
                        chain.service().getRoute().getId(),
                        chain.service().getDeparture().getDepartureTime()));

        activeLineServiceVehicles
                .computeIfAbsent(
                        vehicleEntry.vehicle.getId(),
                        k -> new LinkedList<>())
                .addAll(chain.allServices());

    }

     //TODO: test whether this correctly works with ongoing DRIVE task and insertion at idx 0!!!
    private DrtStopTask addLineService(DvrpVehicle vehicle,
                                       Schedule schedule,
                                       LineService lineService,
                                       Link fromLink,
                                       double fromTime) {

        /*
         * ================================================================
         * Drive to LineService start
         * ================================================================
         */

        Link lineServiceStartLink =
                network.getLinks().get(
                        lineService.getRoute()
                                .getStops()
                                .getFirst()
                                .getStopFacility()
                                .getLinkId());

        VrpPathWithTravelData pathToService =
                VrpPaths.calcAndCreatePath(
                        fromLink,
                        lineServiceStartLink,
                        fromTime,
                        router,
                        travelTime);

        schedule.addTask(
                taskFactory.createDriveTask(
                        vehicle,
                        pathToService,
                        DrtDriveTask.TYPE));

        /*
         * Wait until the scheduled LineService departure if necessary.
         */
        double serviceStartTime = lineService.getDeparture().getDepartureTime();

        if (pathToService.getArrivalTime() < serviceStartTime) {
            schedule.addTask(
                    taskFactory.createStayTask(
                            vehicle,
                            pathToService.getArrivalTime(),
                            serviceStartTime,
                            lineServiceStartLink));
        }

        /*
         * ================================================================
         * Create LineService stops
         * ================================================================
         */

        DrtStopTask previous = null;
        double stopTaskBeginTime = serviceStartTime;

        Gbl.assertIf(
                !lineService.getRoute().getStops().isEmpty());

        for (TransitRouteStop stop :
                lineService.getRoute().getStops()) {

            Link link =
                    network.getLinks().get(
                            stop.getStopFacility().getLinkId());

            if (previous != null) {

                VrpPathWithTravelData toNextStopPath =
                        VrpPaths.calcAndCreatePath(
                                previous.getLink(),
                                link,
                                previous.getEndTime(),
                                router,
                                travelTime);

                DrtDriveTask driveToStopTask =
                        taskFactory.createDriveTask(
                                vehicle,
                                toNextStopPath,
                                DrtDriveTask.TYPE);

                schedule.addTask(driveToStopTask);

                stopTaskBeginTime =
                        driveToStopTask.getEndTime();

                Verify.verify(
                        serviceStartTime
                                + stop.getDepartureOffset().seconds()
                                > stopTaskBeginTime,
                        "It's not possible for vehicle %s to arrive at stop %s (link %s) in time.\n" +
                                " According to schedule, departure should be at %s, but vehicle would only arrive at %s.\n" +
                                " Previous stop task link is %s; route is %s",
                        vehicle.getId(),
                        stop.getStopFacility().getId(),
                        stop.getStopFacility().getLinkId(),
                        serviceStartTime
                                + stop.getDepartureOffset().seconds(),
                        stopTaskBeginTime,
                        previous.getLink().getId(),
                        lineService.getRoute().getId());
            }

            double minimumStopDurationEndTime =
                    stopTaskBeginTime + stop.getMinimumStopDuration();

            double stopTaskEndTime =
                    stop.isAwaitDepartureTime() ?
                            Math.max(serviceStartTime + stop.getArrivalOffset().seconds(), minimumStopDurationEndTime) :
                            minimumStopDurationEndTime;

            DrtStopTask stopTask =
                    new FixedStopTask(
                            taskFactory.createStopTask(
                                    vehicle,
                                    stopTaskBeginTime,
                                    stopTaskEndTime,
                                    link),
                            stop,
                            lineService);

            schedule.addTask(stopTask);

            previous = stopTask;
            stopTaskBeginTime = stopTask.getEndTime();
        }

        //return last FixedStopTask
        return previous;

    }

    public Map<Id<DvrpVehicle>, Queue<LineService>> getActiveLineServiceVehicles() {
        return Collections.unmodifiableMap(activeLineServiceVehicles);
    }

	/**
	 * ends the LineService corresponding to the FixedStopTask, if the FixedStopTask was the last one
	 * @param vehicle
	 * @param fixedStopTask
	 */
    public void arrival(DvrpVehicle vehicle, FixedStopTask fixedStopTask) {
        boolean finished = fixedStopTask.getLineService().advance(fixedStopTask.getStop());
        if(finished) {
            LineService poll = activeLineServiceVehicles.get(vehicle.getId()).poll();
            Verify.verify(poll != null);
            Verify.verify(poll.equals(fixedStopTask.getLineService()));

            //if no line services are scheduled any more for the vehicle, remove it from the list of active vehicles
            if (activeLineServiceVehicles.get(vehicle.getId()).isEmpty()) {
                activeLineServiceVehicles.remove(vehicle.getId());
            }
        }
    }

    private DrtStopTask recreateStopTask(
            DvrpVehicle vehicle,
            DrtStopTask oldStop,
            double beginTime,
            double endTime) {

        if (oldStop instanceof CapacityChangeTask capacityChangeTask) {
            return new DefaultDrtCapacityChangeTask(
                    beginTime,
                    endTime,
                    oldStop.getLink(),
                    capacityChangeTask.getChangedCapacity());
        }

        DrtStopTask newStop = taskFactory.createStopTask(
                vehicle,
                beginTime,
                endTime,
                oldStop.getLink());

        for (AcceptedDrtRequest request : oldStop.getPickupRequests().values()) {
            newStop.addPickupRequest(request);
        }

        for (AcceptedDrtRequest request : oldStop.getDropoffRequests().values()) {
            newStop.addDropoffRequest(request);
        }

        if (oldStop instanceof FixedStopTask fixedStop) {
            return fixedStop.withTimes(newStop);
        }

        return newStop;
    }

    private double addDriveAndWaitIfNecessary(
            DvrpVehicle vehicle,
            Link fromLink,
            double fromTime,
            DrtStopTask targetStop) {

        Schedule schedule = vehicle.getSchedule();

        VrpPathWithTravelData path =
                VrpPaths.calcAndCreatePath(
                        fromLink,
                        targetStop.getLink(),
                        fromTime,
                        router,
                        travelTime);

        schedule.addTask(
                taskFactory.createDriveTask(
                        vehicle,
                        path,
                        DrtDriveTask.TYPE));

        double arrivalTime = path.getArrivalTime();
        double earliestArrivalTime = targetStop.calcEarliestArrivalTime();

        if (arrivalTime < earliestArrivalTime) {
            schedule.addTask(
                    taskFactory.createStayTask(
                            vehicle,
                            arrivalTime,
                            earliestArrivalTime,
                            targetStop.getLink()));

            return earliestArrivalTime;
        }

        return arrivalTime;
    }

    public record LineServiceChain(
            LineService service,
            List<LineService> chainedServices) {

        public List<LineService> allServices() {
            List<LineService> result = new ArrayList<>(1 + chainedServices.size());
            result.add(service);
            result.addAll(chainedServices);
            return result;
        }
    }

}
