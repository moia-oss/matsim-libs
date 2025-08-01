/* *********************************************************************** *
 * project: org.matsim.*
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2011 by the members listed in the COPYING,        *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *   See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                         *
 * *********************************************************************** */

package org.matsim.contrib.decongestion;

import java.util.HashMap;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.contrib.decongestion.data.CongestionInfoWriter;
import org.matsim.contrib.decongestion.data.DecongestionInfo;
import org.matsim.contrib.decongestion.data.LinkInfo;
import org.matsim.contrib.decongestion.handler.DelayAnalysis;
import org.matsim.contrib.decongestion.handler.IntervalBasedTolling;
import org.matsim.contrib.decongestion.tollSetting.DecongestionTollSetting;
import org.matsim.core.config.groups.ReplanningConfigGroup.StrategySettings;
import org.matsim.core.controler.events.AfterMobsimEvent;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.events.IterationStartsEvent;
import org.matsim.core.controler.events.StartupEvent;
import org.matsim.core.controler.listener.AfterMobsimListener;
import org.matsim.core.controler.listener.IterationEndsListener;
import org.matsim.core.controler.listener.IterationStartsListener;
import org.matsim.core.controler.listener.StartupListener;
import org.matsim.core.replanning.GenericPlanStrategy;
import org.matsim.core.replanning.ReplanningUtils;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.utils.charts.XYLineChart;

import com.google.inject.Inject;


/**
 * Interval-based decongestion pricing approach:
 *
 * (1) Identify congested links and time intervals and set an initial toll for these links and time intervals.
 * (2) Let the demand adjust for x iterations.
 * (3) Adjust the tolls (different implementations of {@link DecongestionTollSetting})
 * (4) GOTO (2)
 *
 * All relevant parameters are specified in {@link DecongestionInfo}.
 *
 *
 * @author ikaddoura
 */
class DecongestionControllerListener implements StartupListener, AfterMobsimListener, IterationStartsListener, IterationEndsListener {

	private static final Logger log = LogManager.getLogger(DecongestionControllerListener.class);

	private final SortedMap<Integer, Double> iteration2totalDelay = new TreeMap<>();
	private final SortedMap<Integer, Double> iteration2totalTollPayments = new TreeMap<>();
	private final SortedMap<Integer, Double> iteration2totalTravelTime = new TreeMap<>();
	private final SortedMap<Integer, Double> iteration2userBenefits = new TreeMap<>();

	@Inject
	private DecongestionInfo congestionInfo;

	@Inject(optional=true)
	private DecongestionTollSetting tollComputation;

	@Inject(optional=true)
	private IntervalBasedTolling intervalBasedTolling;

	@Inject(optional=true)
	private DelayAnalysis delayComputation;

	private int nextDisableInnovativeStrategiesIteration = -1;
	private int nextEnableInnovativeStrategiesIteration = -1;

	private String outputDirectory;

	@Override
	public void notifyStartup(StartupEvent event) {

		log.info("decongestion settings: " + congestionInfo.getDecongestionConfigGroup().toString());

		this.outputDirectory = this.congestionInfo.getScenario().getConfig().controller().getOutputDirectory();
		if (!outputDirectory.endsWith("/")) {
			log.info("Adjusting output directory.");
			outputDirectory = outputDirectory + "/";
		}
	}

	@Override
	public void notifyAfterMobsim(AfterMobsimEvent event) {

		if (event.getIteration() % this.congestionInfo.getDecongestionConfigGroup().getWriteOutputIteration() == 0. || event.getIteration() % this.congestionInfo.getDecongestionConfigGroup().getUpdatePriceInterval() == 0.) {
			computeDelays(event);
		}

		if (event.getIteration() == this.congestionInfo.getScenario().getConfig().controller().getFirstIteration()) {
			// skip first iteration

		} else if (event.getIteration() % this.congestionInfo.getDecongestionConfigGroup().getUpdatePriceInterval() == 0.) {

			int totalNumberOfIterations = this.congestionInfo.getScenario().getConfig().controller().getLastIteration() - this.congestionInfo.getScenario().getConfig().controller().getFirstIteration();
			int iterationCounter = event.getIteration() - this.congestionInfo.getScenario().getConfig().controller().getFirstIteration();

			if (iterationCounter < this.congestionInfo.getDecongestionConfigGroup().getFractionOfIterationsToEndPriceAdjustment() * totalNumberOfIterations
					&& iterationCounter > this.congestionInfo.getDecongestionConfigGroup().getFractionOfIterationsToStartPriceAdjustment() * totalNumberOfIterations) {

				if (tollComputation != null) {
					log.info("+++ Iteration " + event.getIteration() + ". Update tolls per link and time bin.");
					tollComputation.updateTolls();
				}
			}
		}

		if (event.getIteration() % this.congestionInfo.getDecongestionConfigGroup().getWriteOutputIteration() == 0.) {
			CongestionInfoWriter.writeDelays(congestionInfo, event.getIteration(), this.outputDirectory + "ITERS/it." + event.getIteration() + "/", this.congestionInfo.getScenario().getConfig().controller().getRunId());
			CongestionInfoWriter.writeTolls(congestionInfo, event.getIteration(), this.outputDirectory + "ITERS/it." + event.getIteration() + "/", this.congestionInfo.getScenario().getConfig().controller().getRunId());
		}
	}

	private void computeDelays(AfterMobsimEvent event) {
		TravelTime travelTime = event.getServices().getLinkTravelTimes();
		double timeBinSize = this.congestionInfo.getScenario().getConfig().travelTimeCalculator().getTraveltimeBinSize();

		for (Link link : this.congestionInfo.getScenario().getNetwork().getLinks().values()) {

			Map<Integer, Double> time2avgDelay = new HashMap<>();

			int timeBinCounter = 0;
			boolean linkHasAtLeastOneTimeBinWithNonZeroAvgDelay = false;

			for (double endTime = timeBinSize ; endTime <= this.congestionInfo.getScenario().getConfig().travelTimeCalculator().getMaxTime(); endTime = endTime + timeBinSize ) {
				final double probedTime = endTime - timeBinSize / 2.;
				double freespeedTravelTime = link.getLength() / link.getFreespeed( probedTime ) ;
				final double congestedTravelTime = travelTime.getLinkTravelTime(link, probedTime, null, null);
				double avgDelay = congestedTravelTime - freespeedTravelTime;

				if (linkHasAtLeastOneTimeBinWithNonZeroAvgDelay == false && avgDelay > this.congestionInfo.getDecongestionConfigGroup().getToleratedAverageDelaySec()) {
					linkHasAtLeastOneTimeBinWithNonZeroAvgDelay = true;
				}

				time2avgDelay.put(timeBinCounter, avgDelay);
				timeBinCounter++;
			}

			if (this.congestionInfo.getlinkInfos().get(link.getId()) != null) {
				this.congestionInfo.getlinkInfos().get(link.getId()).setTime2avgDelay(time2avgDelay);
			} else {

				// only store the linkInfo for links with at least one time bin with a non-zero delay
				if (linkHasAtLeastOneTimeBinWithNonZeroAvgDelay) {
					LinkInfo linkInfo = new LinkInfo(link);
					linkInfo.setTime2avgDelay(time2avgDelay);
					this.congestionInfo.getlinkInfos().put(link.getId(), linkInfo);
				}
			}
		}
	}

	@Override
	public void notifyIterationEnds(IterationEndsEvent event) {

		if (this.delayComputation != null) {
			// Store and write out some aggregated numbers for analysis purposes.

			this.iteration2totalDelay.put(event.getIteration(), this.delayComputation.getTotalDelay());
			double totalPayments = 0.;
			if (this.intervalBasedTolling != null) totalPayments = this.intervalBasedTolling.getTotalTollPayments();
			this.iteration2totalTollPayments.put(event.getIteration(), totalPayments);
			this.iteration2totalTravelTime.put(event.getIteration(), this.delayComputation.getTotalTravelTime());

			double monetizedUserBenefits = 0.;
			for (Person person : this.congestionInfo.getScenario().getPopulation().getPersons().values()) {
				if ( person.getSelectedPlan().getScore()==null ) {
					throw new RuntimeException( "score is null; don't know how to continue.") ;
				}
				monetizedUserBenefits += person.getSelectedPlan().getScore() / this.congestionInfo.getScenario().getConfig().scoring().getMarginalUtilityOfMoney();
			}
			this.iteration2userBenefits.put(event.getIteration(), monetizedUserBenefits);

			CongestionInfoWriter.writeIterationStats(
					this.iteration2totalDelay,
					this.iteration2totalTollPayments,
					this.iteration2totalTravelTime,
					this.iteration2userBenefits,
					outputDirectory,
					this.congestionInfo.getScenario().getConfig().controller().getRunId()
					);

			XYLineChart chart1 = new XYLineChart("Total travel time and total delay", "Iteration", "Hours");
			double[] iterations1 = new double[event.getIteration() + 1];
			double[] values1a = new double[event.getIteration() + 1];
			double[] values1b = new double[event.getIteration() + 1];
			for (int i = this.congestionInfo.getScenario().getConfig().controller().getFirstIteration(); i <= event.getIteration(); i++) {
				iterations1[i] = i;
				values1a[i] = this.iteration2totalDelay.get(i) / 3600.;
				values1b[i] = this.iteration2totalTravelTime.get(i) / 3600.;
			}
			chart1.addSeries("Total delay", iterations1, values1a);
			chart1.addSeries("Total travel time", iterations1, values1b);
			chart1.saveAsPng(outputDirectory + this.congestionInfo.getScenario().getConfig().controller().getRunId() + ".decongestion_travelTime_delay.png", 800, 600);

			XYLineChart chart2 = new XYLineChart("user benefits and toll revenues", "Iteration", "Monetary units");
			double[] iterations2 = new double[event.getIteration() + 1];
			double[] values2b = new double[event.getIteration() + 1];
			double[] values2c = new double[event.getIteration() + 1];

			for (int i = this.congestionInfo.getScenario().getConfig().controller().getFirstIteration(); i <= event.getIteration(); i++) {
				iterations2[i] = i;
				values2b[i] = this.iteration2userBenefits.get(i);
				values2c[i] = this.iteration2totalTollPayments.get(i);
			}
			chart2.addSeries("User benefits", iterations2, values2b);
			chart2.addSeries("Toll payments (decongestion tolls only)", iterations2, values2c);
			chart2.saveAsPng(outputDirectory + this.congestionInfo.getScenario().getConfig().controller().getRunId() + ".decongestion_userBenefits_tolls.png", 800, 600);
		}
	}

	@Override
	public void notifyIterationStarts(IterationStartsEvent event) {

		if (congestionInfo.getDecongestionConfigGroup().getUpdatePriceInterval() > 1) {
			if (event.getIteration() == this.congestionInfo.getScenario().getConfig().controller().getFirstIteration()) {

				this.nextDisableInnovativeStrategiesIteration = (int) (congestionInfo.getScenario().getConfig().replanning().getFractionOfIterationsToDisableInnovation() * congestionInfo.getDecongestionConfigGroup().getUpdatePriceInterval());
				log.info("next disable innovative strategies iteration: " + this.nextDisableInnovativeStrategiesIteration);

				if (this.nextDisableInnovativeStrategiesIteration != 0) {
					this.nextEnableInnovativeStrategiesIteration = (int) (congestionInfo.getDecongestionConfigGroup().getUpdatePriceInterval() + 1);
				}
				log.info("next enable innovative strategies iteration: " + this.nextEnableInnovativeStrategiesIteration);

			} else {

				if (event.getIteration() == this.nextDisableInnovativeStrategiesIteration) {
					// set weight to zero
					log.warn("Strategy weight adjustment (set to zero) in iteration " + event.getIteration());

					for (GenericPlanStrategy<Plan, Person> strategy : event.getServices().getStrategyManager().getStrategies(null)) {

						String strategyName = strategy.toString();
						if (isInnovativeStrategy(strategy)) {
							log.info("Setting weight for " + strategyName + " to zero.");
							event.getServices().getStrategyManager().changeWeightOfStrategy(strategy, null, 0.0);
						}
					}

					this.nextDisableInnovativeStrategiesIteration += congestionInfo.getDecongestionConfigGroup().getUpdatePriceInterval();
					log.info("next disable innovative strategies iteration: " + this.nextDisableInnovativeStrategiesIteration);

				} else if (event.getIteration() == this.nextEnableInnovativeStrategiesIteration) {
					// set weight back to original value

					if (event.getIteration() >= congestionInfo.getScenario().getConfig().replanning().getFractionOfIterationsToDisableInnovation() * (congestionInfo.getScenario().getConfig().controller().getLastIteration() - congestionInfo.getScenario().getConfig().controller().getFirstIteration())) {

						log.info("Strategies are switched off by global settings. Do not set back the strategy parameters to original values...");

					} else {

						log.info("Strategy weight adjustment (set back to original value) in iteration " + event.getIteration());

						for (GenericPlanStrategy<Plan, Person> strategy : event.getServices().getStrategyManager().getStrategies(null)) {

							String strategyName = strategy.toString();
							if (isInnovativeStrategy(strategy)) {

								double originalValue = -1.0;
								for (StrategySettings setting : event.getServices().getConfig().replanning().getStrategySettings()) {
									log.info("setting: " + setting.getStrategyName());
									log.info("strategyName: " + strategyName);

									if (strategyName.contains(setting.getStrategyName())) {
										originalValue = setting.getWeight();
									}
								}

								if (originalValue == -1.0) {
									throw new RuntimeException("Aborting...");
								}

								log.warn("Setting weight for " + strategyName + " back to original value: " + originalValue);
								event.getServices().getStrategyManager().changeWeightOfStrategy(strategy, null, originalValue);
							}
						}
						this.nextEnableInnovativeStrategiesIteration += congestionInfo.getDecongestionConfigGroup().getUpdatePriceInterval();
					}
				}
			}
		}
	}

	private boolean isInnovativeStrategy( GenericPlanStrategy<Plan, Person> strategy) {
		log.info("Strategy name: " + strategy.toString() );
		boolean innovative = ! ( ReplanningUtils.isOnlySelector( strategy ) ) ;
		log.info("Innovative: " + innovative);
		return innovative;
	}
}
