package org.matsim.contrib.drt.extension.alonso_mora.algorithm.function;

import org.matsim.contrib.drt.extension.alonso_mora.algorithm.AlonsoMoraStop;
import org.matsim.contrib.drt.extension.alonso_mora.algorithm.AlonsoMoraVehicle;

import java.util.List;

public interface RouteTracker {
	int update(List<AlonsoMoraStop> stops);

	double getDepartureTime(int index);

	double getArrivalTime(int index);

	int getOccupancyAfter(int index);

	int getOccupancyBefore(int index);

	public void setDrivingState(AlonsoMoraVehicle vehicle);
}
