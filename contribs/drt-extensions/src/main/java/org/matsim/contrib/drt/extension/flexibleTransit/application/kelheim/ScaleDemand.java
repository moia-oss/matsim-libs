package org.matsim.contrib.drt.extension.flexibleTransit.application.kelheim;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.io.PopulationReader;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.scenario.ScenarioUtils;

import java.util.List;
import java.util.Random;

public class ScaleDemand {


    public static void main(String[] args) {

        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        new PopulationReader(scenario).readFile("/Users/nico.kuehnel/Documents/simulation/kelheim/kelheim-kexi.plans2.xml.gz");

        Population output = PopulationUtils.createPopulation(ConfigUtils.createConfig());

        Random random = new Random(444);
        for (Person person : scenario.getPopulation().getPersons().values()) {
            output.addPerson(person);
            if (Math.random() < 0.5) {

                Person copy = output.getFactory().createPerson(Id.createPersonId(person.getId() + "_copy"));
                Plan planCopy = PopulationUtils.createPlan();
                copy.addPlan(planCopy);
                PopulationUtils.copyFromTo(person.getSelectedPlan(), planCopy);

                List<TripStructureUtils.Trip> trips = TripStructureUtils.getTrips(planCopy);
                Activity originActivity = trips.getFirst().getOriginActivity();
                originActivity.setEndTime(random.nextInt(6 * 3600, 23 * 3600));
                double x = originActivity.getCoord().getX() + random.nextDouble(-300,300);
                double y = originActivity.getCoord().getY() + random.nextDouble(-300,300);
                originActivity.setCoord(new Coord(x, y));

                Activity destinationActivity = trips.getFirst().getDestinationActivity();

                double x2 = destinationActivity.getCoord().getX() + random.nextDouble(-300,300);
                double y2 = destinationActivity.getCoord().getY() + random.nextDouble(-300,300);
                destinationActivity.setCoord(new Coord(x2, y2));

                output.addPerson(copy);
            }

        }

        PopulationUtils.writePopulation(output, "/Users/nico.kuehnel/Documents/simulation/kelheim/kelheim-kexi.plans2_50p.xml.gz");


    }

}
