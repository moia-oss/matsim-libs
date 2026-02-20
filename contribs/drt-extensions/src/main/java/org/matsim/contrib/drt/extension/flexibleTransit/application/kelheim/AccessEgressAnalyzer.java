package org.matsim.contrib.drt.extension.flexibleTransit.application.kelheim;

import com.opencsv.CSVReader;
import com.opencsv.CSVWriter;

import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

// A small helper class to store distances
class WalkLegInfo {
    Double access = null;
    Double egress = null;
}

public class AccessEgressAnalyzer {

   // private final static String LEGS_PATH = "/Users/nico.kuehnel/Documents/simulation/kelheim/20251204/flex_moiaCostPrebookingtrue_optionaltrueschedule_rotErwKHStarSandMitterfGronsAuMFliedHeidacker_recheck900s/output_legs.csv.gz";
    private final static String LEGS_PATH = "/Users/nico.kuehnel/Documents/simulation/kelheim/20251204/odm_RegCost_norejPrebookingFalse_schedule_rotErwKHStarSandMitterfGronsAuMFliedHeidackerEinkaufsz35min-1veh/output_legs.csv.gz";
    private final static String OUTPUT_PATH = "accessEgress.csv";

    public static void main(String[] args) throws Exception {

        Map<String, WalkLegInfo> personWalkDistances = new HashMap<>();

        try (CSVReader reader = new CSVReader(new InputStreamReader(new GZIPInputStream(new FileInputStream(LEGS_PATH))))) {
            String[] header = reader.readNext(); // read header
            if (header == null) throw new RuntimeException("Empty input CSV");

            int personIdx = Arrays.asList(header).indexOf("person");
            int modeIdx   = Arrays.asList(header).indexOf("mode");
            int distIdx   = Arrays.asList(header).indexOf("distance");

            if (personIdx < 0 || modeIdx < 0 || distIdx < 0)
                throw new RuntimeException("CSV must contain columns: person, mode, length");

            String[] line;
            while ((line = reader.readNext()) != null) {
                String person = line[personIdx];
                String mode = line[modeIdx];
                double dist = Double.parseDouble(line[distIdx]);

                if (!mode.equalsIgnoreCase("walk")) continue;

                WalkLegInfo info = personWalkDistances.computeIfAbsent(person, p -> new WalkLegInfo());

                if (info.access == null) {
                    info.access = dist;
                } else if (info.egress == null) {
                    info.egress = dist;
                }
            }
        }

        // Write output CSV
        try (CSVWriter writer = new CSVWriter(new FileWriter(OUTPUT_PATH))) {
            writer.writeNext(new String[]{"personId", "accessDistance", "egressDistance"});

            for (Map.Entry<String, WalkLegInfo> e : personWalkDistances.entrySet()) {
                String person = e.getKey();
                WalkLegInfo d = e.getValue();
                writer.writeNext(new String[]{
                        person,
                        d.access != null ? d.access.toString() : "",
                        d.egress != null ? d.egress.toString() : ""
                });
            }
        }

        double meanAccess = personWalkDistances.values().stream().filter(w -> w.egress != null).mapToDouble(walk -> walk.access).average().getAsDouble();
        double meanEgress = personWalkDistances.values().stream().filter(w -> w.egress != null).mapToDouble(walk -> walk.egress).average().getAsDouble();
        double grandMean = personWalkDistances.values().stream().filter(w -> w.egress != null).flatMap(w -> Stream.of(w.access, w.egress)).mapToDouble(walk -> walk).average().getAsDouble();

        System.out.println("✔ Done! Wrote file: " + OUTPUT_PATH);
        System.out.println("Average access: " + meanAccess);
        System.out.println("Average egress: " + meanEgress);
        System.out.println("Grand mean : " + grandMean);
    }
}
