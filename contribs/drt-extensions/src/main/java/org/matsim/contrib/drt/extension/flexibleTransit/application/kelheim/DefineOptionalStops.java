package org.matsim.contrib.drt.extension.flexibleTransit.application.kelheim;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.TransitScheduleReader;
import org.matsim.pt.transitSchedule.api.TransitScheduleWriter;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

public class DefineOptionalStops {

    public static final String SCHEDULE_PATH = "/Users/nico.kuehnel/Documents/simulation/kelheim/schedule.xml";

    public static void main(String[] args) {

        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        new TransitScheduleReader(scenario).readFile(SCHEDULE_PATH);

        TransitStopFacility bahnhof = scenario.getTransitSchedule().getFacilities().get(Id.create("Bahnhof", TransitStopFacility.class));

        bahnhof.getAttributes().putAttribute("drt:circle-line:circle-antiClockwise:next", "KelheimwinzerStrasse");
        bahnhof.getAttributes().putAttribute("drt:circle-line:circle-antiClockwise:previous", "Schuetzenheim");
        new TransitScheduleWriter(scenario.getTransitSchedule()).writeFile(SCHEDULE_PATH);


    }
}
