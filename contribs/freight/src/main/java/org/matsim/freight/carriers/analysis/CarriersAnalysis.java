/*
 *   *********************************************************************** *
 *   project: org.matsim.*
 *   *********************************************************************** *
 *                                                                           *
 *   copyright       : (C)  by the members listed in the COPYING,        *
 *                     LICENSE and WARRANTY file.                            *
 *   email           : info at matsim dot org                                *
 *                                                                           *
 *   *********************************************************************** *
 *                                                                           *
 *     This program is free software; you can redistribute it and/or modify  *
 *     it under the terms of the GNU General Public License as published by  *
 *     the Free Software Foundation; either version 2 of the License, or     *
 *     (at your option) any later version.                                   *
 *     See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                           *
 *   ***********************************************************************
 *
 */

package org.matsim.freight.carriers.analysis;

import java.io.File;
import java.nio.file.Path;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.application.ApplicationUtils;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.freight.carriers.Carriers;
import org.matsim.freight.carriers.CarriersUtils;
import org.matsim.freight.carriers.FreightCarriersConfigGroup;
import org.matsim.freight.carriers.events.CarrierEventsReaders;

//import static org.matsim.application.ApplicationUtils.globFile;


/**
 * A first approach for some analysis based on the freight events introduced in 2022/23.
 * This class comes from teaching SimGV in the winter term 2022/23.
 * <p>
 * This class should get extended and prepared as a standardized analysis for freight output.
 * This should also get aligned with the current development in Simwrapper.
 * Todo: Add some tests.
 *
 * @author kturner (Kai Martins-Turner)
 */
public class CarriersAnalysis {

	private static final Logger log = LogManager.getLogger(CarriersAnalysis.class);

	//Where is your simulation output, that should be analysed?
	private String EVENTS_PATH = null;
	private final String ANALYSIS_OUTPUT_PATH;
	private Scenario scenario = null;
	private Carriers carriers = null;

	public enum CarrierAnalysisType {
		/**
		 * Analyzes only the unplanned part of the carriers
		 */
		carriersPlans_unPlanned,
		/**
		 * Analyzes the complete carriers plans including the selected tours.
		 */
		carriersPlans,
		/**
		 * Analyzes the complete carriers plans and adds an event-based analysis of the carriers based on vehicles, vehicleTypes and carriers.
		 */
		carriersAndEvents
	}

	private final CarrierAnalysisType defaultAnalysisType = CarrierAnalysisType.carriersAndEvents;

	/**
	 * This constructor automatically searches for the necessary output file in a simulation run output.
	 * The default folder for the analysis results is "CarriersAnalysis".
	 *
	 * @param simOutputPath The output directory of the simulation run
	 */
	public CarriersAnalysis(String simOutputPath) {
		this(simOutputPath, Path.of(simOutputPath).resolve("analysis").resolve("freight").toString(), null);
	}

	/**
	 * This constructor automatically searches for the necessary output file in a simulation run output.
	 *
	 * @param simOutput      The output directory of the simulation run
	 * @param analysisOutputPath The directory where the result of the analysis should go to
	 * @param globalCrs          The CRS of the simulation
	 */
	public CarriersAnalysis(String simOutput, String analysisOutputPath, String globalCrs) {

		this.ANALYSIS_OUTPUT_PATH = analysisOutputPath;
		Path simOutputPath = Path.of(simOutput);
		this.EVENTS_PATH = ApplicationUtils.globFile(simOutputPath, "*output_events.*").toString();
		String vehiclesPath = ApplicationUtils.globFile(simOutputPath, "*output_allVehicles.*").toString();
		String networkPath = ApplicationUtils.globFile(simOutputPath, "*output_network.*").toString();
		String carriersPath = ApplicationUtils.globFile(simOutputPath, "*output_carriers.*").toString();
		String carriersVehicleTypesPath = ApplicationUtils.globFile(simOutputPath, "*output_carriersVehicleTypes.*").toString();

		createScenarioForCarriersAnalysis(vehiclesPath, networkPath, carriersPath, carriersVehicleTypesPath, globalCrs);
	}

	/**
	 * Alternative if you want to set the paths to the necessary resources directly.
	 *
	 * @param networkPath              Path to the network file
	 * @param vehiclesPath             Path to the vehicle file
	 * @param carriersPath             Path to the carriers file
	 * @param carriersVehicleTypesPath Path to the carriersVehicleTypes file
	 * @param eventsPath               Path to the events file
	 * @param analysisOutputPath       Path to the output directory
	 * @param globalCrs                The CRS of the simulation
	 */
	public CarriersAnalysis(String networkPath, String vehiclesPath, String carriersPath, String carriersVehicleTypesPath, String eventsPath, String analysisOutputPath, String globalCrs) {
		this.EVENTS_PATH = eventsPath;
		this.ANALYSIS_OUTPUT_PATH = analysisOutputPath;

		createScenarioForCarriersAnalysis(vehiclesPath, networkPath, carriersPath, carriersVehicleTypesPath, globalCrs);
	}

	/**
	 * Constructor, if you only want to have the carrier analysis.
	 *
	 * @param carriers           The carriers to be analyzed
	 * @param analysisOutputPath The directory where the result of the analysis should go to
	 */
	public CarriersAnalysis(Carriers carriers, String analysisOutputPath) {
		this.carriers = carriers;
		this.ANALYSIS_OUTPUT_PATH = analysisOutputPath;
	}

	private void createScenarioForCarriersAnalysis(String vehiclesPath, String networkPath, String carriersPath, String carriersVehicleTypesPath, String globalCrs) {
		log.info("########## Starting Carriers Analysis ##########");

		Config config = ConfigUtils.createConfig();
		config.vehicles().setVehiclesFile(vehiclesPath);
		config.network().setInputFile(networkPath);
		config.plans().setInputFile(null);
		config.eventsManager().setNumberOfThreads(null);
		config.eventsManager().setEstimatedNumberOfEvents(null);
		config.global().setNumberOfThreads(1);
		config.global().setCoordinateSystem(globalCrs);

		//freight settings
		FreightCarriersConfigGroup freightCarriersConfigGroup = ConfigUtils.addOrGetModule(config, FreightCarriersConfigGroup.class);
		freightCarriersConfigGroup.setCarriersFile(carriersPath);
		freightCarriersConfigGroup.setCarriersVehicleTypesFile(carriersVehicleTypesPath);

		scenario = ScenarioUtils.loadScenario(config);

		//load carriers according to freight config
		CarriersUtils.loadCarriersAccordingToFreightConfig(scenario);
		this.carriers = CarriersUtils.addOrGetCarriers(scenario);
	}

	/**
	 * Runs the carriers analysis based on the default analysis type.
	 */
	public void runCarrierAnalysis() {
		log.info("No analysis type selected. Running default analysis type: {}", defaultAnalysisType);
		runCarrierAnalysis(defaultAnalysisType);
	}


	/**
	 * Runs the carriers analysis based on the selected analysis type.
	 *
	 * @param analysisType The type of the analysis
	 */
	public void runCarrierAnalysis(CarrierAnalysisType analysisType) {
		File folder = new File(String.valueOf(ANALYSIS_OUTPUT_PATH));
		if (!folder.exists()) {
			//noinspection ResultOfMethodCallIgnored
			folder.mkdirs();
		}
		String delimiter = "\t";
		CarrierPlanAnalysis carrierPlanAnalysis = new CarrierPlanAnalysis(delimiter, carriers);
		switch (analysisType) {
			case carriersPlans_unPlanned -> carrierPlanAnalysis.runAnalysisAndWriteStats(ANALYSIS_OUTPUT_PATH, CarrierAnalysisType.carriersPlans_unPlanned);
			case carriersPlans -> carrierPlanAnalysis.runAnalysisAndWriteStats(ANALYSIS_OUTPUT_PATH, CarrierAnalysisType.carriersPlans);
			case carriersAndEvents -> {
				carrierPlanAnalysis.runAnalysisAndWriteStats(ANALYSIS_OUTPUT_PATH, CarrierAnalysisType.carriersAndEvents);

				// Prepare eventsManager - start of event based Analysis;
				EventsManager eventsManager = EventsUtils.createEventsManager();

				CarrierTimeAndDistanceAnalysis carrierTimeAndDistanceAnalysis = new CarrierTimeAndDistanceAnalysis(
					delimiter, scenario);
				eventsManager.addHandler(carrierTimeAndDistanceAnalysis);

				CarrierLoadAnalysis carrierLoadAnalysis = new CarrierLoadAnalysis(delimiter);
				eventsManager.addHandler(carrierLoadAnalysis);

				eventsManager.initProcessing();
				MatsimEventsReader matsimEventsReader = CarrierEventsReaders.createEventsReader(eventsManager);

				matsimEventsReader.readFile(EVENTS_PATH);
				eventsManager.finishProcessing();

				log.info("Analysis completed.");
				log.info("Writing output...");
				carrierTimeAndDistanceAnalysis.writeTravelTimeAndDistancePerVehicle(ANALYSIS_OUTPUT_PATH, scenario);
				carrierTimeAndDistanceAnalysis.writeTravelTimeAndDistancePerVehicleType(ANALYSIS_OUTPUT_PATH, scenario);
				carrierTimeAndDistanceAnalysis.writeTravelTimeAndDistancePerCarrier(ANALYSIS_OUTPUT_PATH, scenario);
				carrierLoadAnalysis.writeLoadPerVehicle(ANALYSIS_OUTPUT_PATH, scenario);
			}
		}
	}
}
