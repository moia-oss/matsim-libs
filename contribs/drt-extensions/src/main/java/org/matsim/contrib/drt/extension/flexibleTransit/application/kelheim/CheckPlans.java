package org.matsim.contrib.drt.extension.flexibleTransit.application.kelheim;

import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.population.PopulationWriter;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.TripStructureUtils;

import java.util.List;

public class CheckPlans {

    public static void main(String[] args) {


        Population population = PopulationUtils.readPopulation("/Users/nico.kuehnel/IdeaProjects/drt-scenario-library/scenarios/kelheim-kexi/kelheim-kexi.plans.xml.gz");


        Network network = NetworkUtils.readNetwork("/Users/nico.kuehnel/IdeaProjects/drt-scenario-library/scenarios/kelheim-kexi/kelheim-drt.network.xml.gz");



        Population out = PopulationUtils.createPopulation(ConfigUtils.createConfig());


        for (Person p : population.getPersons().values()) {
            List<Activity> activities = TripStructureUtils.getActivities(p.getSelectedPlan(), TripStructureUtils.StageActivityHandling.ExcludeStageActivities);
            activities.forEach(activity -> {
                activity.setCoord(network.getLinks().get(activity.getLinkId()).getCoord());
            });
            out.addPerson(p);
        }


        new PopulationWriter(out).write("/Users/nico.kuehnel/IdeaProjects/drt-scenario-library/scenarios/kelheim-kexi/kelheim-kexi.plans-with-coords.xml.gz");

    }

}
