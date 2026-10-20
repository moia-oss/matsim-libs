/*
 * Copyright (C) 2026 MOIA GmbH
 *
 * You may use, distribute and modify this code under the terms
 * of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License,
 * or (at your option) any later version.
 */
package org.matsim.contrib.drt.extension.operations.remoteoperations;

/**
 * The roster of field support agents L_F, read from the shift input file by their own shift type. A thin holder around
 * a {@link RemoteGuidanceOperators} registry, so it can be bound next to the remote operators' registry without the two
 * being confused. Field agents supervise no vehicles, so every agent has capacity 0.
 *
 * @author lpfeil / MOIA
 */
public record FieldSupportAgents(RemoteGuidanceOperators registry) {
}
