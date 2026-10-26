package org.matsim.contrib.drt.prebooking;

import com.google.common.base.Verify;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.*;
import org.matsim.contrib.dvrp.passenger.PassengerRequestScheduledEvent;
import org.matsim.core.mobsim.framework.MobsimAgent;
import org.matsim.core.mobsim.qsim.InternalInterface;
import org.matsim.core.mobsim.qsim.agents.WithinDayAgentUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.*;
import org.matsim.facilities.ActivityFacilities;
import org.matsim.facilities.FacilitiesUtils;
import org.matsim.facilities.Facility;

import java.util.ArrayList;
import java.util.List;

public class DynamicAccessEgressHandler {

	private final String mode;

	private InternalInterface internalInterface;

	private final ActivityFacilities facilities;
	private final Population population;

	private final RoutingModule accessRouter;
	private final RoutingModule egressRouter;


	private final Network network;

	public DynamicAccessEgressHandler(String mode, ActivityFacilities facilities, Population population,
									  RoutingModule accessRouter, RoutingModule egressRouter, Network network) {
		this.mode = mode;
		this.facilities = facilities;
		this.population = population;
		this.accessRouter = accessRouter;
		this.egressRouter = egressRouter;
		this.network = network;
	}

	public void handle(PassengerRequestScheduledEvent event) {
		Id<Link> pickupLink = event.getPickupLink();
		LinkWrapperFacility accessFacility = new LinkWrapperFacility(network.getLinks().get(pickupLink));
		Id<Link> dropoffLink = event.getDropoffLink();
		LinkWrapperFacility egressFacility = new LinkWrapperFacility(network.getLinks().get(dropoffLink));

		for (Id<Person> personId : event.getPersonIds()) {
			MobsimAgent agent = internalInterface.getMobsim().getAgents().get(personId);
			PlanElement startActivity = WithinDayAgentUtils.getCurrentPlanElement(agent);
			Plan plan = WithinDayAgentUtils.getModifiablePlan(agent);


			Activity temp = null;
			if(!(startActivity instanceof Activity)) {
				for (PlanElement planElement : WithinDayAgentUtils.subList(agent, WithinDayAgentUtils.getCurrentPlanElementIndex(agent), plan.getPlanElements().size())) {
					if(planElement instanceof Activity activity && !StageActivityTypeIdentifier.isStageActivity(activity.getType())) {
						temp = activity;
					} else if (planElement instanceof Leg) {
						if(temp != null && ((Leg) planElement).getMode().equals(TransportMode.drt)) {
							startActivity = temp;
							break;
						}
					}
				}
			} else {
				TripStructureUtils.Trip trip = TripStructureUtils.findTripStartingAtActivity((Activity) startActivity, plan);
				if(trip.getLegsOnly().size() > 1) {
					for (PlanElement planElement : WithinDayAgentUtils.subList(agent, plan.getPlanElements().indexOf(startActivity) +1, plan.getPlanElements().size())) {
						if(planElement instanceof Activity activity && !StageActivityTypeIdentifier.isStageActivity(activity.getType())) {
							temp = activity;
						} else if (planElement instanceof Leg) {
							if(temp != null && ((Leg) planElement).getMode().equals(TransportMode.drt)) {
								startActivity = temp;
								break;
							}
						}
					}
				}
			}


			Verify.verify(startActivity instanceof Activity, "Expected activity, got %s", startActivity);
			Activity currentActivity = (Activity) startActivity;
			Facility originFacility = FacilitiesUtils.toFacility(currentActivity, facilities);
			Person person = population.getPersons().get(personId);

			TripStructureUtils.Trip trip = TripStructureUtils.findTripStartingAtActivity(currentActivity, plan);
			Verify.verifyNotNull(trip, "No trip found for activity %s", currentActivity);

			Facility destinationFacility = FacilitiesUtils.toFacility(trip.getDestinationActivity(), facilities);

			List<Leg> legs = trip.getLegsOnly();
			Verify.verifyNotNull(legs, "No legs found for trip %s", trip);
			Verify.verify(legs.size() == 1, "Expected single drt leg trip, got %s", trip);
			Leg leg = legs.getFirst();
			Verify.verify(leg.getMode().equals(TransportMode.drt), "Expected drt leg, got %s", leg);

			// access (sub-)trip:
			List<PlanElement> accessTrip = new ArrayList<>(accessRouter.calcRoute(
					DefaultRoutingRequest.of(originFacility, accessFacility, ((Activity) startActivity).getEndTime().seconds(), person, null)));
			if (!accessTrip.isEmpty()) {
				// interaction activity:
				accessTrip.add(createDrtStageActivity(accessFacility));
				plan.getPlanElements().addAll(plan.getPlanElements().indexOf(leg), accessTrip);
			}

			// egress (sub-)trip:
			List<PlanElement> egressTrip = new ArrayList<>(egressRouter.calcRoute(
					DefaultRoutingRequest.of(egressFacility, destinationFacility, event.getDropoffTime(), person,null)));
			if (!egressTrip.isEmpty()) {
				// interaction activity:
				egressTrip.addFirst(createDrtStageActivity(egressFacility));
				plan.getPlanElements().addAll(plan.getPlanElements().indexOf(leg) + 1, egressTrip);
			}
		}
	}

	public void setInternalInterface(InternalInterface internalInterface) {
		this.internalInterface = internalInterface;
	}


	/**
	 * copied from {@link org.matsim.contrib.dvrp.router.DvrpRoutingModule}
	 */
	private Activity createDrtStageActivity(Facility stopFacility) {
		return PopulationUtils.createActivity(PopulationUtils.createStageActivityFromCoordLinkIdAndModePrefix(stopFacility.getCoord(),
				stopFacility.getLinkId(), mode));
	}

}
