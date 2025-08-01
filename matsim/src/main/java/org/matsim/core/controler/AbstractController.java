/* *********************************************************************** *
 * project: org.matsim.*
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2012 by the members listed in the COPYING,        *
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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.analysis.IterationStopWatch;
import org.matsim.core.config.Config;
import org.matsim.core.config.groups.ControllerConfigGroup;
import org.matsim.core.controler.listener.ControllerListener;
import org.matsim.core.gbl.MatsimRandom;
import org.matsim.utils.MemoryObserver;

import java.io.UncheckedIOException;

/*package*/ abstract class AbstractController {
    // we already had one case where a method of this was removed, causing downstream failures; better just not
	// offer it at all; framework with injector should now be flexible enough.  kai, mar'18

    private static final  Logger log = LogManager.getLogger(AbstractController.class);

    private OutputDirectoryHierarchy controllerIO;

    private final IterationStopWatch stopwatch;


    /**
     * This is deliberately not even protected.  kai, jul'12
     */
    private ControllerListenerManagerImpl controllerListenerManagerImpl;


    private Integer thisIteration = null;

    protected AbstractController() {
        this(new ControllerListenerManagerImpl(), new IterationStopWatch(), null);
    }

    AbstractController(ControllerListenerManagerImpl controllerListenerManager, IterationStopWatch stopWatch, MatsimServices matsimServices) {
        log.info("Used Controler-Class: " + this.getClass().getCanonicalName());
        this.controllerListenerManagerImpl = controllerListenerManager;
        this.controllerListenerManagerImpl.setControler(matsimServices);
        this.stopwatch = stopWatch;
    }

    private void resetRandomNumbers(long seed, int iteration) {
        MatsimRandom.reset(seed + iteration);
        MatsimRandom.getRandom().nextDouble(); // draw one because of strange
        // "not-randomness" is the first draw...
        // Fixme [kn] this should really be ten thousand draws instead of just one
    }

    final void setupOutputDirectory(OutputDirectoryHierarchy controlerIO) {
        this.controllerIO = controlerIO;
        OutputDirectoryLogging.initLogging(this.getControlerIO()); // logging needs to be early
    }

    protected final void run(final Config config) {
        MemoryObserver.start(config.controller().getMemoryObserverInterval());
        MatsimRuntimeModifications.MyRunnable runnable = new MatsimRuntimeModifications.MyRunnable() {
            @Override
            public void run() throws MatsimRuntimeModifications.UnexpectedShutdownException {
                loadCoreListeners();
                controllerListenerManagerImpl.fireControllerStartupEvent();
                ControllerUtils.checkConfigConsistencyAndWriteToLog(config, "config dump before iterations start" );
                prepareForSim();
                doIterations(config);
            }

            @Override
            public void shutdown(boolean unexpected, Throwable exception) {
                controllerListenerManagerImpl.fireControllerShutdownEvent(unexpected, thisIteration == null ? -1 : thisIteration, exception);
            }
        };
        MatsimRuntimeModifications.run(runnable);
        OutputDirectoryLogging.closeOutputDirLogging();
        MemoryObserver.stop();
    }

    protected abstract void loadCoreListeners();

    protected abstract void runMobSim();

    protected abstract void prepareForSim();

    protected abstract void prepareForMobsim() ;

    /**
     * Stopping criterion for iterations.  Design thoughts:<ul>
     * <li> AbstractController only controls process, not content.  Stopping iterations controls process based on content.
     * All such coupling methods are abstract; thus this one has to be abstract, too.
     * <li> One can see this confirmed in the KnSimplifiedControler use case, where the function is delegated to a static
     * method in the SimplifiedControllerUtils class ... as with all other abstract methods.
     * </ul>
     */
	protected abstract boolean mayTerminateAfterIteration(int iteration);
	protected abstract boolean shouldTerminate(int iteration);

    private void doIterations(Config config) throws MatsimRuntimeModifications.UnexpectedShutdownException {
    	int iteration = config.controller().getFirstIteration();

    	// Special case if lastIteration == -1 -> Do not run any Mobsim
    	boolean doTerminate = config.controller().getLastIteration() < iteration;

    	while (!doTerminate) {
    		boolean isLastIteration = mayTerminateAfterIteration(iteration);
    		iteration(config, iteration, isLastIteration);
    		doTerminate = isLastIteration && shouldTerminate(iteration);
    		iteration++;
    	}
    }

    final String MARKER = "### ";

    private void iteration(final Config config, final int iteration, boolean isLastIteration) throws MatsimRuntimeModifications.UnexpectedShutdownException {
        this.thisIteration = iteration;
        this.getStopwatch().beginIteration(iteration);

        log.info(Controler.DIVIDER);
        log.info(MARKER + "ITERATION " + iteration + " BEGINS");
        this.getControlerIO().createIterationDirectory(iteration);
        resetRandomNumbers(config.global().getRandomSeed(), iteration);

        iterationStep("iterationStartsListeners", new Runnable() {
            @Override
            public void run() {
                controllerListenerManagerImpl.fireControllerIterationStartsEvent(iteration, isLastIteration);
            }
        });

        if (iteration > config.controller().getFirstIteration()) {
            iterationStep("replanning", new Runnable() {
                @Override
                public void run() {
                    controllerListenerManagerImpl.fireControllerReplanningEvent(iteration, isLastIteration);
                }
            });
        }

        mobsim(config, iteration, isLastIteration);

        iterationStep("scoring", new Runnable() {
            @Override
            public void run() {
                log.info(MARKER + "ITERATION " + iteration + " fires scoring event");
                controllerListenerManagerImpl.fireControllerScoringEvent(iteration, isLastIteration);
            }
        });

        iterationStep("iterationEndsListeners", new Runnable() {
            @Override
            public void run() {
                log.info(MARKER + "ITERATION " + iteration + " fires iteration end event");
                controllerListenerManagerImpl.fireControllerIterationEndsEvent(iteration, isLastIteration);
            }
        });

        this.getStopwatch().endIteration();
        try {
            this.getStopwatch().writeSeparatedFile(this.getControlerIO().getOutputFilename("stopwatch.csv"), config.global().getDefaultDelimiter());
        } catch (UncheckedIOException e) {
            log.error("Could not write stopwatch file.", e);
        }
        if (config.controller().getCreateGraphsInterval() > 0 && iteration % config.controller().getCreateGraphsInterval() == 0) {
            this.getStopwatch().writeGraphFile(this.getControlerIO().getOutputFilename("stopwatch", ControllerConfigGroup.CompressionType.none));
        }
        log.info(MARKER + "ITERATION " + iteration + " ENDS");
        log.info(Controler.DIVIDER);
    }

    private void mobsim(final Config config, final int iteration, boolean isLastIteration) throws MatsimRuntimeModifications.UnexpectedShutdownException {
        // ControllerListeners may create managed resources in
        // beforeMobsim which need to be cleaned up in afterMobsim.
        // Hence the finally block.
        // For instance, ParallelEventsManagerImpl leaves Threads waiting if we don't do this
        // and an Exception occurs in the Mobsim.
        try {
            iterationStep("beforeMobsimListeners", new Runnable() {
                @Override
                public void run() {
                    controllerListenerManagerImpl.fireControllerBeforeMobsimEvent(iteration, isLastIteration);
                }
            });

            iterationStep( "prepareForMobsim", new Runnable(){
                    @Override
                public void run() {
                        prepareForMobsim() ;
                        // todo: make replacable
                }
            }) ;

            iterationStep("mobsim", new Runnable() {
                @Override
                public void run() {
                    resetRandomNumbers(config.global().getRandomSeed(), iteration);
                    runMobSim();
                }
            });
        }
        catch ( Throwable t ) {
			// I had problems with an exception being thrown in my MobsimFactory: when the after mobsim
			// listeners were called from the finally block, the finishProcessing() method of the events
			// manager also resulted in an exception (because the mobsim crashed before initProcessing() was
			// ever called), "hidding" the actual source of the problem.
			// To avoid this, we log anything thrown during mobsim before executing after mobsim listeners.
			// td, oct'14
			log.error(  "Mobsim did not complete normally! afterMobsimListeners will be called anyway." , t  );

			// Java 7 seems able to detect which throwables this can be, thus no
			// need to wrap or anything... Nice!
			// If an exception occurs in the finally-block, this exception will be
			// suppressed, but at least we logged it.
			throw t;
        }
        finally {
            iterationStep("afterMobsimListeners", new Runnable() {
                @Override
                public void run() {
                    log.info(MARKER + "ITERATION " + iteration + " fires after mobsim event");
                    controllerListenerManagerImpl.fireControllerAfterMobsimEvent(iteration, isLastIteration);
                }
            });
        }
    }

    private void iterationStep(String iterationStepName, Runnable iterationStep) throws MatsimRuntimeModifications.UnexpectedShutdownException {
        this.getStopwatch().beginOperation(iterationStepName);
        iterationStep.run();
        this.getStopwatch().endOperation(iterationStepName);
        if (Thread.interrupted()) {
            throw new MatsimRuntimeModifications.UnexpectedShutdownException();
        }
    }


    /**
     * Design comments:<ul>
     * <li> This is such that ControllerListenerManager does not need to be exposed.  One may decide otherwise ...  kai, jul'12
     * </ul>
     */
    public final void addControllerListener(ControllerListener l) {
        this.controllerListenerManagerImpl.addControllerListener(l);
    }

		@Deprecated(since = "2025-07-19")
    public final void addControlerListener(ControllerListener l) {
			this.addControllerListener(l);
		}

    protected final void addCoreControllerListener(ControllerListener l) {
        this.controllerListenerManagerImpl.addCoreControllerListener(l);
    }

		@Deprecated(since = "2025-07-19")
    protected final void addCoreControlerListener(ControllerListener l) {
			this.addCoreControllerListener(l);
		}

    public final OutputDirectoryHierarchy getControlerIO() {
        return controllerIO;
    }


	public final Integer getIterationNumber() {
		return this.thisIteration;
	}

    public IterationStopWatch getStopwatch() {
        return stopwatch;
    }

}
