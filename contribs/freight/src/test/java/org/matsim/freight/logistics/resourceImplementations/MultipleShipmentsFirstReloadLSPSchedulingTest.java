/*
 *  *********************************************************************** *
 *  * project: org.matsim.*
 *  * *********************************************************************** *
 *  *                                                                         *
 *  * copyright       : (C) 2022 by the members listed in the COPYING,        *
 *  *                   LICENSE and WARRANTY file.                            *
 *  * email           : info at matsim dot org                                *
 *  *                                                                         *
 *  * *********************************************************************** *
 *  *                                                                         *
 *  *   This program is free software; you can redistribute it and/or modify  *
 *  *   it under the terms of the GNU General Public License as published by  *
 *  *   the Free Software Foundation; either version 2 of the License, or     *
 *  *   (at your option) any later version.                                   *
 *  *   See also COPYING, LICENSE and WARRANTY file                           *
 *  *                                                                         *
 *  * ***********************************************************************
 */

package org.matsim.freight.logistics.resourceImplementations;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Map.Entry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.config.Config;
import org.matsim.core.events.handler.EventHandler;
import org.matsim.core.gbl.MatsimRandom;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.freight.carriers.*;
import org.matsim.freight.carriers.CarrierCapabilities.FleetSize;
import org.matsim.freight.logistics.*;
import org.matsim.freight.logistics.resourceImplementations.ResourceImplementationUtils.TranshipmentHubSchedulerBuilder;
import org.matsim.freight.logistics.resourceImplementations.ResourceImplementationUtils.TransshipmentHubBuilder;
import org.matsim.freight.logistics.shipment.LspShipment;
import org.matsim.freight.logistics.shipment.LspShipmentPlanElement;
import org.matsim.freight.logistics.shipment.LspShipmentUtils;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

public class MultipleShipmentsFirstReloadLSPSchedulingTest {
	private LSP lsp;
	private LSPResource firstTranshipmentHubResource;
	private LogisticChainElement firstHubElement;
	private LogisticChainElement collectionElement;
	private LSPResource collectionResource;

	@BeforeEach
	public void initialize() {
		Config config = new Config();
		config.addCoreModules();
		Scenario scenario = ScenarioUtils.createScenario(config);
		new MatsimNetworkReader(scenario.getNetwork()).readFile("scenarios/2regions/2regions-network.xml");
		Network network = scenario.getNetwork();


		Id<Carrier> collectionCarrierId = Id.create("CollectionCarrier", Carrier.class);
		Id<VehicleType> collectionVehTypeId = Id.create("CollectionCarrierVehicleType", VehicleType.class);
		org.matsim.vehicles.VehicleType collectionVehType = VehicleUtils.createVehicleType(collectionVehTypeId, TransportMode.car);
		collectionVehType.getCapacity().setOther(10);
		collectionVehType.getCostInformation().setCostsPerMeter(0.0004);
		collectionVehType.getCostInformation().setCostsPerSecond(0.38);
		collectionVehType.getCostInformation().setFixedCost(49.);
		collectionVehType.setMaximumVelocity(50/3.6);

		Id<Link> collectionLinkId = Id.createLinkId("(4 2) (4 3)");
		Id<Vehicle> collectionVehicleId = Id.createVehicleId("CollectionVehicle");
		CarrierVehicle collectionCarrierVehicle = CarrierVehicle.newInstance(collectionVehicleId, collectionLinkId, collectionVehType);

		CarrierCapabilities.Builder collectionCapabilitiesBuilder = CarrierCapabilities.Builder.newInstance();
		collectionCapabilitiesBuilder.addVehicle(collectionCarrierVehicle);
		collectionCapabilitiesBuilder.setFleetSize(FleetSize.INFINITE);
		CarrierCapabilities collectionCapabilities = collectionCapabilitiesBuilder.build();
		Carrier collectionCarrier = CarriersUtils.createCarrier(collectionCarrierId);
		collectionCarrier.setCarrierCapabilities(collectionCapabilities);

		collectionResource = ResourceImplementationUtils.CollectionCarrierResourceBuilder.newInstance(collectionCarrier)
				.setCollectionScheduler(ResourceImplementationUtils.createDefaultCollectionCarrierScheduler(scenario))
				.setLocationLinkId(collectionLinkId)
				.build();

		collectionElement = LSPUtils.LogisticChainElementBuilder.newInstance(Id.create("CollectionElement", LogisticChainElement.class))
				.setResource(collectionResource)
				.build();

		TranshipmentHubSchedulerBuilder firstReloadingSchedulerBuilder = ResourceImplementationUtils.TranshipmentHubSchedulerBuilder.newInstance();
		firstReloadingSchedulerBuilder.setCapacityNeedFixed(10);
		firstReloadingSchedulerBuilder.setCapacityNeedLinear(1);


		Id<LSPResource> firstTransshipmentHubId = Id.create("TranshipmentHub1", LSPResource.class);
		Id<Link> firstTransshipmentHub_LinkId = Id.createLinkId("(4 2) (4 3)");

		TransshipmentHubBuilder firstTransshipmentHubBuilder = ResourceImplementationUtils.TransshipmentHubBuilder.newInstance(firstTransshipmentHubId, firstTransshipmentHub_LinkId, scenario);
		firstTransshipmentHubBuilder.setTransshipmentHubScheduler(firstReloadingSchedulerBuilder.build());
		firstTranshipmentHubResource = firstTransshipmentHubBuilder.build();

		Id<LogisticChainElement> firstHubElementId = Id.create("FirstHubElement", LogisticChainElement.class);
		LSPUtils.LogisticChainElementBuilder firstHubElementBuilder = LSPUtils.LogisticChainElementBuilder.newInstance(firstHubElementId);
		firstHubElementBuilder.setResource(firstTranshipmentHubResource);
		firstHubElement = firstHubElementBuilder.build();

		collectionElement.connectWithNextElement(firstHubElement);


		Id<LogisticChain> solutionId = Id.create("SolutionId", LogisticChain.class);
		LSPUtils.LogisticChainBuilder completeSolutionBuilder = LSPUtils.LogisticChainBuilder.newInstance(solutionId);
		completeSolutionBuilder.addLogisticChainElement(collectionElement);
		completeSolutionBuilder.addLogisticChainElement(firstHubElement);
		LogisticChain completeSolution = completeSolutionBuilder.build();

		InitialShipmentAssigner assigner = ResourceImplementationUtils.createSingleLogisticChainShipmentAssigner();
		LSPPlan completePlan = LSPUtils.createLSPPlan();
		completePlan.setInitialShipmentAssigner(assigner);
		completePlan.addLogisticChain(completeSolution);

		LSPUtils.LSPBuilder completeLSPBuilder = LSPUtils.LSPBuilder.getInstance(Id.create("CollectionLSP", LSP.class));
		completeLSPBuilder.setInitialPlan(completePlan);
		ArrayList<LSPResource> resourcesList = new ArrayList<>();
		resourcesList.add(collectionResource);
		resourcesList.add(firstTranshipmentHubResource);


		LogisticChainScheduler simpleScheduler = ResourceImplementationUtils.createDefaultSimpleForwardLogisticChainScheduler(resourcesList);
		simpleScheduler.setBufferTime(300);
		completeLSPBuilder.setLogisticChainScheduler(simpleScheduler);
		lsp = completeLSPBuilder.build();

		ArrayList<Link> linkList = new ArrayList<>(network.getLinks().values());

		for (int i = 1; i < 100; i++) {
			Id<LspShipment> id = Id.create(i, LspShipment.class);
			LspShipmentUtils.LspShipmentBuilder builder = LspShipmentUtils.LspShipmentBuilder.newInstance(id);
			//Random random = new Random(1);
			int capacityDemand = MatsimRandom.getRandom().nextInt(4);
			builder.setCapacityDemand(capacityDemand);

			while (true) {
				Collections.shuffle(linkList);
				Link pendingToLink = linkList.getFirst();
				if ((pendingToLink.getFromNode().getCoord().getX() <= 18000 &&
						pendingToLink.getFromNode().getCoord().getY() <= 4000 &&
						pendingToLink.getFromNode().getCoord().getX() >= 14000 &&
						pendingToLink.getToNode().getCoord().getX() <= 18000 &&
						pendingToLink.getToNode().getCoord().getY() <= 4000 &&
						pendingToLink.getToNode().getCoord().getX() >= 14000)) {
					builder.setToLinkId(pendingToLink.getId());
					break;
				}

			}

			while (true) {
				Collections.shuffle(linkList);
				Link pendingFromLink = linkList.getFirst();
				if (pendingFromLink.getFromNode().getCoord().getX() <= 4000 &&
						pendingFromLink.getFromNode().getCoord().getY() <= 4000 &&
						pendingFromLink.getToNode().getCoord().getX() <= 4000 &&
						pendingFromLink.getToNode().getCoord().getY() <= 4000) {
					builder.setFromLinkId(pendingFromLink.getId());
					break;
				}

			}

			TimeWindow endTimeWindow = TimeWindow.newInstance(0, (24 * 3600));
			builder.setEndTimeWindow(endTimeWindow);
			TimeWindow startTimeWindow = TimeWindow.newInstance(0, (24 * 3600));
			builder.setStartTimeWindow(startTimeWindow);
			builder.setDeliveryServiceTime(capacityDemand * 60);
			LspShipment shipment = builder.build();
			lsp.assignShipmentToLSP(shipment);
		}
		lsp.scheduleLogisticChains();

	}

	@Test
	public void testFirstReloadLSPScheduling() {

		for (LspShipment shipment : lsp.getLspShipments()) {
			ArrayList<LspShipmentPlanElement> scheduleElements = new ArrayList<>(LspShipmentUtils.getOrCreateShipmentPlan(lsp.getSelectedPlan(), shipment.getId()).getPlanElements().values());
			scheduleElements.sort(LspShipmentUtils.createShipmentPlanElementComparator());

			System.out.println();
			for (int i = 0; i < LspShipmentUtils.getOrCreateShipmentPlan(lsp.getSelectedPlan(), shipment.getId()).getPlanElements().size(); i++) {
				System.out.println("Scheduled: " + scheduleElements.get(i).getLogisticChainElement().getId() + "  " + scheduleElements.get(i).getResourceId() + "  " + scheduleElements.get(i).getElementType() + " Start: " + scheduleElements.get(i).getStartTime() + " End: " + scheduleElements.get(i).getEndTime());
			}
			System.out.println();
		}


		for (LspShipment shipment : lsp.getLspShipments()) {
			assertEquals(4, LspShipmentUtils.getOrCreateShipmentPlan(lsp.getSelectedPlan(), shipment.getId()).getPlanElements().size());
			ArrayList<LspShipmentPlanElement> planElements = new ArrayList<>(LspShipmentUtils.getOrCreateShipmentPlan(lsp.getSelectedPlan(), shipment.getId()).getPlanElements().values());
			planElements.sort(LspShipmentUtils.createShipmentPlanElementComparator());
			assertEquals("HANDLE", planElements.get(3).getElementType());
			assertTrue(planElements.get(3).getEndTime() >= (0));
			assertTrue(planElements.get(3).getEndTime() <= (24*3600));
			assertTrue(planElements.get(3).getStartTime() <= planElements.get(3).getEndTime());
			assertTrue(planElements.get(3).getStartTime() >= (0));
			assertTrue(planElements.get(3).getStartTime() <= (24*3600));
			assertSame(planElements.get(3).getResourceId(), firstTranshipmentHubResource.getId());
			assertSame(planElements.get(3).getLogisticChainElement(), firstHubElement);

			assertEquals(planElements.get(3).getStartTime(), planElements.get(2).getEndTime() + 300, 0.0);

			assertEquals("UNLOAD", planElements.get(2).getElementType());
			assertTrue(planElements.get(2).getEndTime() >= (0));
			assertTrue(planElements.get(2).getEndTime() <= (24*3600));
			assertTrue(planElements.get(2).getStartTime() <= planElements.get(2).getEndTime());
			assertTrue(planElements.get(2).getStartTime() >= (0));
			assertTrue(planElements.get(2).getStartTime() <= (24*3600));
			assertSame(planElements.get(2).getResourceId(), collectionResource.getId());
			assertSame(planElements.get(2).getLogisticChainElement(), collectionElement);

			assertEquals(planElements.get(2).getStartTime(), planElements.get(1).getEndTime(), 0.0);

			assertEquals("TRANSPORT", planElements.get(1).getElementType());
			assertTrue(planElements.get(1).getEndTime() >= (0));
			assertTrue(planElements.get(1).getEndTime() <= (24*3600));
			assertTrue(planElements.get(1).getStartTime() <= planElements.get(1).getEndTime());
			assertTrue(planElements.get(1).getStartTime() >= (0));
			assertTrue(planElements.get(1).getStartTime() <= (24*3600));
			assertSame(planElements.get(1).getResourceId(), collectionResource.getId());
			assertSame(planElements.get(1).getLogisticChainElement(), collectionElement);

			assertEquals(planElements.get(1).getStartTime(), planElements.get(0).getEndTime(), 0.0);

			assertEquals("LOAD", planElements.get(0).getElementType());
			assertTrue(planElements.get(0).getEndTime() >= (0));
			assertTrue(planElements.getFirst().getEndTime() <= (24*3600));
			assertTrue(planElements.getFirst().getStartTime() <= planElements.getFirst().getEndTime());
			assertTrue(planElements.getFirst().getStartTime() >= (0));
			assertTrue(planElements.getFirst().getStartTime() <= (24*3600));
			assertSame(planElements.getFirst().getResourceId(), collectionResource.getId());
			assertSame(planElements.getFirst().getLogisticChainElement(), collectionElement);

		}

		assertEquals(1, firstTranshipmentHubResource.getSimulationTrackers().size());
		ArrayList<EventHandler> eventHandlers = new ArrayList<>(firstTranshipmentHubResource.getSimulationTrackers());
		assertInstanceOf(TransshipmentHubTourEndEventHandler.class, eventHandlers.getFirst());
		TransshipmentHubTourEndEventHandler reloadEventHandler = (TransshipmentHubTourEndEventHandler) eventHandlers.getFirst();

		for (Entry<CarrierService, TransshipmentHubTourEndEventHandler.TransshipmentHubEventHandlerPair> entry : reloadEventHandler.getServicesWaitedFor().entrySet()) {
			CarrierService service = entry.getKey();
			LspShipment shipment = entry.getValue().lspShipment;
			LogisticChainElement element = entry.getValue().logisticChainElement;
			assertSame(service.getServiceLinkId(), shipment.getFrom());
            assertEquals(service.getDemand(), shipment.getSize());
			assertEquals(service.getServiceDuration(), shipment.getDeliveryServiceTime(), 0.0);
			boolean handledByTranshipmentHub = false;
			for (LogisticChainElement clientElement : reloadEventHandler.getTranshipmentHub().getClientElements()) {
				if (clientElement == element) {
					handledByTranshipmentHub = true;
					break;
				}
			}
			assertTrue(handledByTranshipmentHub);

			assertTrue(element.getOutgoingShipments().getLspShipmentsWTime().contains(shipment));
			assertFalse(element.getIncomingShipments().getLspShipmentsWTime().contains(shipment));
		}


		for (LspShipment shipment : lsp.getLspShipments()) {
			assertEquals(2, shipment.getSimulationTrackers().size());
			eventHandlers = new ArrayList<>(shipment.getSimulationTrackers());
			ArrayList<LspShipmentPlanElement> planElements = new ArrayList<>(LspShipmentUtils.getOrCreateShipmentPlan(lsp.getSelectedPlan(), shipment.getId()).getPlanElements().values());

			assertInstanceOf(LSPTourEndEventHandler.class, eventHandlers.getFirst());
			LSPTourEndEventHandler endHandler = (LSPTourEndEventHandler) eventHandlers.getFirst();
			assertSame(endHandler.getCarrierService().getServiceLinkId(), shipment.getFrom());
            assertEquals(endHandler.getCarrierService().getDemand(), shipment.getSize());
			assertEquals(endHandler.getCarrierService().getServiceDuration(), shipment.getDeliveryServiceTime(), 0.0);
			assertEquals(endHandler.getCarrierService().getServiceStartTimeWindow().getStart(), shipment.getPickupTimeWindow().getStart(), 0.0);
			assertEquals(endHandler.getCarrierService().getServiceStartTimeWindow().getEnd(), shipment.getPickupTimeWindow().getEnd(), 0.0);
			assertSame(endHandler.getLogisticChainElement(), planElements.get(2).getLogisticChainElement());
			assertSame(endHandler.getLogisticChainElement(), lsp.getSelectedPlan().getLogisticChains().iterator().next().getLogisticChainElements().iterator().next());
			assertSame(endHandler.getLspShipment(), shipment);
			assertSame(endHandler.getResourceId(), planElements.get(2).getResourceId());
			assertSame(endHandler.getResourceId(), lsp.getResources().iterator().next().getId());

			assertInstanceOf(CollectionServiceEndEventHandler.class, eventHandlers.get(1));
			CollectionServiceEndEventHandler serviceHandler = (CollectionServiceEndEventHandler) eventHandlers.get(1);
			assertSame(serviceHandler.getCarrierService().getServiceLinkId(), shipment.getFrom());
            assertEquals(serviceHandler.getCarrierService().getDemand(), shipment.getSize());
			assertEquals(serviceHandler.getCarrierService().getServiceDuration(), shipment.getDeliveryServiceTime(), 0.0);
			assertEquals(serviceHandler.getCarrierService().getServiceStartTimeWindow().getStart(), shipment.getPickupTimeWindow().getStart(), 0.0);
			assertEquals(serviceHandler.getCarrierService().getServiceStartTimeWindow().getEnd(), shipment.getPickupTimeWindow().getEnd(), 0.0);
			assertSame(serviceHandler.getElement(), planElements.getFirst().getLogisticChainElement());
			assertSame(serviceHandler.getElement(), lsp.getSelectedPlan().getLogisticChains().iterator().next().getLogisticChainElements().iterator().next());
			assertSame(serviceHandler.getLspShipment(), shipment);
			assertSame(serviceHandler.getResourceId(), planElements.getFirst().getResourceId());
			assertSame(serviceHandler.getResourceId(), lsp.getResources().iterator().next().getId());
		}

		for (LogisticChain solution : lsp.getSelectedPlan().getLogisticChains()) {
			for (LogisticChainElement element : solution.getLogisticChainElements()) {
				assertTrue(element.getIncomingShipments().getLspShipmentsWTime().isEmpty());
				if (element.getNextElement() != null) {
					assertTrue(element.getOutgoingShipments().getLspShipmentsWTime().isEmpty());
				} else {
					assertFalse(element.getOutgoingShipments().getLspShipmentsWTime().isEmpty());
				}
			}
		}
	}
}