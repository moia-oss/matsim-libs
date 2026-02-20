package org.matsim.contrib.drt.extension.flexibleTransit;

import org.matsim.contrib.dvrp.load.DvrpLoad;
import org.matsim.contrib.dvrp.load.DvrpLoadType;
import org.matsim.contrib.dvrp.load.IntegersLoad;
import org.matsim.contrib.dvrp.load.IntegersLoadType;

import java.util.List;
import java.util.Map;

public final class MoiaDvrpLoadType implements DvrpLoadType {

	public static final int PASSENGERS_INDEX = 0;
	public static final int PASSENGERS_WAV_INDEX = 1;
	private final IntegersLoadType delegate;

	public MoiaDvrpLoadType() {
		delegate = new IntegersLoadType("passengers", "passengers_wav");
	}

	public IntegersLoad getLoad(int passengers, int passengersWav) {
		return delegate.fromArray(passengers, passengersWav);
	}

	public static int getNumberOfPassengers(IntegersLoad load) {
		return load.getElement(PASSENGERS_INDEX).intValue();
	}

	public static int getNumberOfPassengersWAV(IntegersLoad load) {
		return load.getElement(PASSENGERS_WAV_INDEX).intValue();
	}

	@Override
	public DvrpLoad fromMap(Map<String, Number> map) {
		return delegate.fromMap(map);
	}

	@Override
	public DvrpLoad getEmptyLoad() {
		return delegate.getEmptyLoad();
	}

	@Override
	public List<String> getDimensions() {
		return delegate.getDimensions();
	}

	@Override
	public int size() {
		return delegate.size();
	}

	@Override
	public DvrpLoad deserialize(String representation) {
		return delegate.deserialize(representation);
	}

	@Override
	public String serialize(DvrpLoad load) {
		return delegate.serialize(load);
	}
}
