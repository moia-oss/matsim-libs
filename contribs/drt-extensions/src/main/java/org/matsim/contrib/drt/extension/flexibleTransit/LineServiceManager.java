package org.matsim.contrib.drt.extension.flexibleTransit;

import com.google.common.base.Verify;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.IdMap;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.drt.optimizer.VehicleEntry;
import org.matsim.contrib.drt.schedule.DrtDriveTask;
import org.matsim.contrib.drt.schedule.DrtStayTask;
import org.matsim.contrib.drt.schedule.DrtStopTask;
import org.matsim.contrib.drt.schedule.DrtTaskFactory;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.contrib.dvrp.fleet.Fleet;
import org.matsim.contrib.dvrp.path.VrpPathWithTravelData;
import org.matsim.contrib.dvrp.path.VrpPaths;
import org.matsim.contrib.dvrp.schedule.Schedule;
import org.matsim.contrib.dvrp.schedule.Schedules;
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

    private final SortedSet<LineService> services;
    private final DrtTaskFactory taskFactory;

    private final Network network;
    private final TravelTime travelTime;
    private final LeastCostPathCalculator router;

    private final VehicleEntry.EntryFactory vehicleEntryFactory;
    private final ForkJoinPool forkJoinPool;
    private final Fleet fleet;
    private final EventsManager eventsManager;


    public LineServiceManager(TransitSchedule transitSchedule, DrtTaskFactory taskFactory, Network network,
                              TravelTime travelTime, LeastCostPathCalculator router, ForkJoinPool forkJoinPool,
                              VehicleEntry.EntryFactory vehicleEntryFactory, Fleet fleet, EventsManager eventsManager) {
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
                        .comparingDouble((ToDoubleFunction<LineService>) value -> value.getDeparture().getDepartureTime())
                        .thenComparing(o -> o.getRoute().getId())
        );

        Set<Id<Departure>> skippedChainedDepartures = new HashSet<>();

        for (TransitLine transitLine : transitSchedule.getTransitLines().values()) {
            for (TransitRoute transitRoute : transitLine.getRoutes().values()) {
                for (Departure departure : transitRoute.getDepartures().values()) {
                    if(skippedChainedDepartures.contains(departure.getId())) {
                        continue;
                    }
                    LineService lineService = new LineService(transitLine, transitRoute, departure);
                    services.add(lineService);
                    processChain(transitRoute, departure, skippedChainedDepartures);
                }
            }
        }
    }

    private static void processChain(TransitRoute transitRoute, Departure departure, Set<Id<Departure>> skippedChainedDepartures) {
        departure.getChainedDepartures().forEach(departureChain -> {
            skippedChainedDepartures.add(departureChain.getChainedDepartureId());
            Departure chainedDeparture = transitRoute.getDepartures().get(departureChain.getChainedDepartureId());
            processChain(transitRoute, chainedDeparture, skippedChainedDepartures);
        });
    }


    public void doSimStep(double now) {

        List<LineService> schedulableServices = new ArrayList<>();
        for (LineService lineService : services) {
            if (lineService.getDeparture().getDepartureTime() > now + 1800) {
                break;
            }
            if(lineService.getDeparture().getDepartureTime() == now + 1800) {
                schedulableServices.add(lineService);
            }
        }


        if (!schedulableServices.isEmpty()) {
            var vehicleEntries = forkJoinPool.submit(() -> fleet.getVehicles()
                    .values()
                    .parallelStream()
                    .map(v -> vehicleEntryFactory.create(v, now))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toMap(e -> e.vehicle.getId(), e -> e))).join();


            Iterator<LineService> lineServiceIterator = schedulableServices.iterator();

            while(lineServiceIterator.hasNext()) {

                LineService schedulableService = lineServiceIterator.next();

                VehicleEntry selected = null;
                for (VehicleEntry vehicleEntry : vehicleEntries.values()) {

                    //TODO:
                    // actually we need to iterate over all stops and see if there is a slack gap large enough to fit hte whole line service

                    if (vehicleEntry.vehicle.getServiceBeginTime() <= now && (vehicleEntry.stops.isEmpty()
                            || vehicleEntry.stops.getLast().getDepartureTime() < schedulableService.getDeparture().getDepartureTime() - 900)
                            && schedulableService.getRoute().getStops().getLast().getArrivalOffset().seconds() < vehicleEntry.vehicle.getServiceEndTime()) {
                        //TODO:
                        //route estimation
                        scheduleLineService(schedulableService, vehicleEntry, now);
                        selected = vehicleEntry;
                        break;
                    }
                }
                if(selected != null) {
                    vehicleEntries.remove(selected.vehicle.getId());
                    VehicleEntry updated = vehicleEntryFactory.create(selected.vehicle, now);
                    if(updated != null) {
                        vehicleEntries.put(selected.vehicle.getId(), updated);
                    }
                    lineServiceIterator.remove();
                }
            }
        }
    }

    private void scheduleLineService(LineService schedulableService, VehicleEntry vehicleEntry, double now) {
        scheduleDeparture(vehicleEntry, now, schedulableService);
        for (ChainedDeparture chainedDeparture : schedulableService.getDeparture().getChainedDepartures()) {
            Departure departure = schedulableService.getRoute().getDepartures().get(chainedDeparture.getChainedDepartureId());
            LineService chainedService = new LineService(schedulableService.getLine(), schedulableService.getRoute(), departure);
            VehicleEntry updatedEntry = vehicleEntryFactory.create(vehicleEntry.vehicle, now);
            scheduleLineService(chainedService, updatedEntry, now);
        }
    }


    private void scheduleDeparture(VehicleEntry vehicleEntry, double now, LineService schedulableService) {

        double toServiceDepartureTime;
        Schedule schedule = vehicleEntry.vehicle.getSchedule();
        if(vehicleEntry.stops.isEmpty()) {
            DrtStayTask currentTask = (DrtStayTask) schedule.getCurrentTask();

            while (!(currentTask == Schedules.getLastTask(schedule))) {
                schedule.removeLastTask();
            }
            currentTask.setEndTime(now);
            toServiceDepartureTime = now;
        } else {
            DrtStopTask lastStopInSchedule = vehicleEntry.stops.getLast().getTask();
            DrtStayTask lastStayTask = (DrtStayTask) schedule.getTasks().get(lastStopInSchedule.getTaskIdx() + 1);
            schedule.removeTask(lastStayTask);
            if(!Schedules.getLastTask(schedule).equals(lastStayTask)) {
                //    throw new RuntimeException("Shouldn't happen.");
            }
            toServiceDepartureTime = lastStopInSchedule.getEndTime();
        }

        Link lastLinkInSchedule = Schedules.getLastLinkInSchedule(vehicleEntry.vehicle);

        Link lineServiceStartLink = network.getLinks().get(schedulableService.getRoute().getStops().getFirst().getStopFacility().getLinkId());
        VrpPathWithTravelData path = VrpPaths.calcAndCreatePath(lastLinkInSchedule, lineServiceStartLink, toServiceDepartureTime, this.router, this.travelTime);
        DrtDriveTask driveTask = taskFactory.createDriveTask(vehicleEntry.vehicle, path, DrtDriveTask.TYPE);
        schedule.addTask(driveTask);

        double serviceStartTime = schedulableService.getDeparture().getDepartureTime();
        if (path.getArrivalTime() < serviceStartTime) {
            schedule.addTask(taskFactory.createStayTask(vehicleEntry.vehicle, path.getArrivalTime(), serviceStartTime, lineServiceStartLink));
        }

        DrtStopTask previous = null;
        Gbl.assertIf(!schedulableService.getRoute().getStops().isEmpty());
        double stopTaskBeginTime = serviceStartTime;
        for (TransitRouteStop stop : schedulableService.getRoute().getStops()) {

            Link link = network.getLinks().get(stop.getStopFacility().getLinkId());
            if(previous != null) {
                VrpPathWithTravelData toNextStopPath = VrpPaths.calcAndCreatePath(previous.getLink(), link, previous.getEndTime(), this.router, this.travelTime);
                DrtDriveTask driveToStopTask = taskFactory.createDriveTask(vehicleEntry.vehicle, toNextStopPath, DrtDriveTask.TYPE);
                schedule.addTask(driveToStopTask);
                stopTaskBeginTime = driveToStopTask.getEndTime();
                Verify.verify(serviceStartTime + stop.getDepartureOffset().seconds() > stopTaskBeginTime,
                        "It's not possible for vehicle %s to arrive at stop %s in time", vehicleEntry.vehicle.getId(), stop.getStopFacility().getId());
            }

            double minimumStopDurationEndTime = stopTaskBeginTime + stop.getMinimumStopDuration();
            double stopTaskEndTime = stop.isAwaitDepartureTime() ? Math.max(serviceStartTime + stop.getArrivalOffset().seconds(), minimumStopDurationEndTime) : minimumStopDurationEndTime;

            DrtStopTask stopTask = new FixedStopTask(taskFactory.createStopTask(vehicleEntry.vehicle,
                    stopTaskBeginTime,
                    stopTaskEndTime,
                    link), stop, schedulableService);

            schedule.addTask(stopTask);
            previous = stopTask;
            stopTaskBeginTime = stopTask.getEndTime();
        }
        schedule.addTask(taskFactory.createStayTask(vehicleEntry.vehicle, previous.getEndTime(), vehicleEntry.vehicle.getServiceEndTime(), previous.getLink()));

        eventsManager.processEvent(new LineServiceScheduledEvent(now, "drt", vehicleEntry.vehicle.getId(), schedulableService.getLine().getId(), schedulableService.getRoute().getId(), serviceStartTime));
        activeLineServiceVehicles.computeIfAbsent(vehicleEntry.vehicle.getId(), k -> new LinkedList<>()).add(schedulableService);
    }

    private final Map<Id<DvrpVehicle>, Queue<LineService>> activeLineServiceVehicles = new IdMap<>(DvrpVehicle.class);

    public Map<Id<DvrpVehicle>, Queue<LineService>> getActiveLineServiceVehicles() {
        return Collections.unmodifiableMap(activeLineServiceVehicles);
    }

    public void arrival(DvrpVehicle vehicle, FixedStopTask fixedStopTask) {
        boolean finished = fixedStopTask.getLineService().advance(fixedStopTask.getStop());
        if(finished) {
            LineService poll = activeLineServiceVehicles.get(vehicle.getId()).poll();
            Verify.verify(poll != null);
            Verify.verify(poll.equals(fixedStopTask.getLineService()));
        }
    }
}
