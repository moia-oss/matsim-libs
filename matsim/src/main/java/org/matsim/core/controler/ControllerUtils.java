/* *********************************************************************** *
 * project: org.matsim.*												   *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2008 by the members listed in the COPYING,        *
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
package org.matsim.core.controler;

import java.io.PrintWriter;
import java.io.StringWriter;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigWriter;
import org.matsim.core.controler.corelisteners.ControlerDefaultCoreListenersModule;
import org.matsim.core.controler.events.AbstractIterationEvent;
import org.matsim.core.scenario.ScenarioByInstanceModule;

/**
 * @author nagel
 *
 */
public final class ControllerUtils{
	private static final Logger log = LogManager.getLogger( ControllerUtils.class ) ;
	/**
	 * This is meant for creating the matsim injector if one does not need/want {@link Controler}.  Technical reason is that {@link Controler} creates
	 * the injector in the run method, and then it is too late to extract material in a direct way.
	 *
	 * @param config
	 * @param scenario
	 * @return
	 */
	public static com.google.inject.Injector createAdhocInjector( Config config, Scenario scenario ){
		return Injector.createInjector( config, new AbstractModule(){
			@Override public void install(){
				install( new NewControlerModule() );
				install( new ControlerDefaultCoreListenersModule() );
				install( new ControlerDefaultsModule() );
				install( new ScenarioByInstanceModule( scenario ) );
			}
		} );
	}
	/**
	 * This is meant for creating the matsim injector if one does not need/want {@link Controler}.
	 * Technical reason is that {@link Controler} creates
	 * the injector in the run method, and then it is too late to extract material in a direct way.
	 *
	 * @param scenario
	 * @return
	 */
	public static com.google.inject.Injector createAdhocInjector( Scenario scenario ){
		return Injector.createInjector( scenario.getConfig(), new AbstractModule(){
			@Override public void install(){
				install( new NewControlerModule() );
				install( new ControlerDefaultCoreListenersModule() );
				install( new ControlerDefaultsModule() );
				install( new ScenarioByInstanceModule( scenario ) );
			}
		} );
	}

	private ControllerUtils() {} // namespace for static methods only should not be instantiated

	/**
	 * Design decisions:
	 * <ul>
	 * <li>I extracted this method since it is now called <i>twice</i>: once
	 * directly after reading, and once before the iterations start. The second
	 * call seems more important, but I wanted to leave the first one there in
	 * case the program fails before that config dump. Might be put into the
	 * "unexpected shutdown hook" instead. kai, dec'10
	 *
	 * Removed the first call for now, because I am now also checking for
	 * consistency with loaded controler modules. If still desired, we can
	 * put it in the shutdown hook.. michaz aug'14
	 *
	 * </ul>
	 *
	 * @param config  TODO
	 * @param message the message that is written just before the config dump
	 */
	public static final void checkConfigConsistencyAndWriteToLog(Config config,
	                                                                final String message) {
	    log.info(message);
	    String newline = System.lineSeparator();// use native line endings for logfile
	    StringWriter writer = new StringWriter();
	    new ConfigWriter(config).writeStream(new PrintWriter(writer), newline);

		if (log.getLevel().isMoreSpecificThan(Level.DEBUG)) {
			log.info("=== logging config.xml skipped ===");
			log.info("To enable debug output, set an environment variable i.e. export LOG_LEVEL='debug', "
				+ "or set log.setLogLevel(Level.DEBUG) in your run class.");
		}
	    log.debug(newline + newline + writer.getBuffer().toString());
	    log.info("Complete config dump done.");
	    log.info("Checking consistency of config...");
	    config.checkConsistency();
	    log.info("Checking consistency of config done.");
	}

	public static void catchLogEntries() {
		OutputDirectoryLogging.catchLogEntries();
	}

	public static Controller createController( Scenario scenario ) {
		return new Controler( scenario );
	}

	/**
	 * Decides if some functionality should be active in the current iteration if it is
	 * configured to run only at a specific iteration-interval.
	 */
	public static boolean isIterationActive(AbstractIterationEvent event, int interval) {
		if (interval <= 0) {
			return false;
		}
		return event.isLastIteration() || (event.getIteration() % interval == 0);
	}

}
