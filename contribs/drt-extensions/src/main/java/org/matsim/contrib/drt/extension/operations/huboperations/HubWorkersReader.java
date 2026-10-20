/*
 * Copyright (C) 2026 MOIA GmbH
 *
 * You may use, distribute and modify this code under the terms
 * of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License,
 * or (at your option) any later version.
 */
package org.matsim.contrib.drt.extension.operations.huboperations;

import org.matsim.api.core.v01.Id;
import org.matsim.contrib.drt.extension.operations.operationFacilities.OperationFacility;
import org.matsim.core.utils.io.MatsimXmlParser;
import org.xml.sax.Attributes;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Stack;

/**
 * Reads the hub worker roster. One {@code worker} element per worker:
 * <pre>
 * &lt;hubWorkers&gt;
 *     &lt;worker id="w0" hub="1" start="14400" end="43200"/&gt;
 *     &lt;worker id="w1" hub="1" start="43200" end="79200"/&gt;
 *     &lt;worker id="m0" hub="1" start="28800" end="57600" pool="maintenance"/&gt;
 * &lt;/hubWorkers&gt;
 * </pre>
 * {@code hub} is the id of an operation facility, {@code start} and {@code end} are seconds of the day. The optional
 * {@code pool} is {@code cleaning} (the default, the hub crew L_C) or {@code maintenance} (the technicians L_M). The
 * number of workers of a pool at a hub is the number of elements that name both.
 *
 * @author lpfeil / MOIA
 */
public class HubWorkersReader extends MatsimXmlParser {

	private static final String ROOT = "hubWorkers";
	private static final String WORKER = "worker";
	private static final String ID = "id";
	private static final String HUB = "hub";
	private static final String START = "start";
	private static final String END = "end";
	private static final String POOL = "pool";

	private final Map<Id<HubWorkers.HubWorker>, HubWorkers.Worker> workers = new LinkedHashMap<>();

	public HubWorkersReader() {
		super(ValidationType.NO_VALIDATION);
		setValidating(false);
	}

	@Override
	public void startTag(String name, Attributes atts, Stack<String> context) {
		switch (name) {
			case ROOT:
				break;
			case WORKER:
				Id<HubWorkers.HubWorker> id = Id.create(atts.getValue(ID), HubWorkers.HubWorker.class);
				HubWorkers.Worker worker = new HubWorkers.Worker(id,
						Id.create(atts.getValue(HUB), OperationFacility.class),
						Double.parseDouble(atts.getValue(START)),
						Double.parseDouble(atts.getValue(END)),
						atts.getValue(POOL) == null ? HubWorkers.Pool.cleaning : HubWorkers.Pool.valueOf(atts.getValue(POOL)));
				if (workers.put(id, worker) != null) {
					throw new IllegalArgumentException("duplicate hub worker id " + id);
				}
				break;
			default:
				throw new IllegalArgumentException("unknown tag " + name + " in hub worker file, context " + context);
		}
	}

	@Override
	public void endTag(String name, String content, Stack<String> context) {
	}

	public HubWorkers getHubWorkers() {
		return new HubWorkers(workers);
	}
}
