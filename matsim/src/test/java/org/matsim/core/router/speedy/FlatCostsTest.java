package org.matsim.core.router.speedy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class FlatCostsTest {

	private static final double BIN = 900.0;
	private static final int BINS = 4; // samples for bins 0..4, where sample 4 is the time at the horizon

	/**
	 * <pre>{@code
	 *   link 0 (constant)   12 12 12 12 12
	 *   link 1 (varying)    10 10 40 25 25      bins:   |  0  |  1  |  2  |  3  | horizon
	 *                                                     0   900  1800  2700  3600 s
	 * }</pre>
	 */
	private static FlatCosts costs() {
		FlatCosts flat = new FlatCosts(2, BIN, BINS);
		flat.set(0, new double[] {12, 12, 12, 12, 12}, new double[] {120, 120, 120, 120, 120});
		flat.set(1, new double[] {10, 10, 40, 25, 25}, new double[] {100, 100, 400, 250, 250});
		return flat;
	}

	@Test
	void aConstantLinkHasTheSameValueAtEveryTime() {
		FlatCosts flat = costs();
		for (double time : new double[] {-5, 0, 899, 900, 2000, 3600, 1e9}) {
			assertEquals(12, flat.travelTime(0, time), time + " s");
			assertEquals(120, flat.cost(0, time), time + " s");
		}
	}

	@Test
	void aVaryingLinkChangesExactlyAtBinBoundaries() {
		FlatCosts flat = costs();
		assertEquals(10, flat.travelTime(1, 1799.999));
		assertEquals(40, flat.travelTime(1, 1800));
		assertEquals(40, flat.travelTime(1, 2699.999));
		assertEquals(25, flat.travelTime(1, 2700));
		assertEquals(400, flat.cost(1, 1800));
	}

	@Test
	void timesBeforeZeroUseTheFirstSampleAndTimesFromTheHorizonOnTheLast() {
		FlatCosts flat = costs();
		assertEquals(10, flat.travelTime(1, -100));
		assertEquals(25, flat.travelTime(1, 3600));
		assertEquals(25, flat.travelTime(1, 30 * 3600));
	}
}
