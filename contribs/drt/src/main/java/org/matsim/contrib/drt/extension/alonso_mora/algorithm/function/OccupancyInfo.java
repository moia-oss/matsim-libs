package org.matsim.contrib.drt.extension.alonso_mora.algorithm.function;

import java.util.*;
import java.util.function.Function;

public class OccupancyInfo {

    public final static String WAV = "WAV";
    public final static String REGULAR = "REGULAR";

    private final Map<String, LinkedList<Integer>> occupancy = new HashMap<>();
    private final Map<String, Integer> initialOccupancy = new HashMap<>();

    public int getInitialOccupancy(String key) {
        return initialOccupancy.get(key);
    }

    public void setInitialOccupancy(String key, int value) {
        initialOccupancy.put(key, value);
    }

    public List<Integer> getOccupancies(String key) {
        return occupancy.computeIfAbsent(key, s -> new LinkedList<>());
    }

    public void setOccupancy(String key, LinkedList<Integer> occupancies) {
        occupancy.put(key, occupancies);
    }

    public Map<String, LinkedList<Integer>> getAllOccupancies() {
        return Collections.unmodifiableMap(occupancy);
    }
}
