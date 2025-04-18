
/* *********************************************************************** *
 * project: org.matsim.*
 * PersonUtils.java
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2019 by the members listed in the COPYING,        *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *   See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                         *
 * *********************************************************************** */

package org.matsim.core.population;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.scoring.functions.ModeUtilityParameters;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import java.util.*;

public final class PersonUtils {
	private final static String SEX_ATTRIBUTE = "sex";
	private final static String HAS_LICENSE = "hasLicense";
	private static final String CAR_AVAIL = "carAvail";
	private static final String EMPLOYED = "employed";
	private static final String AGE = "age";
	private static final String TRAVEL_CARDS = "travelcards";
	private static final String PERSONAL_INCOME_ATTRIBUTE_NAME = "income";
	private static final String PERSONAL_SCORING_MODE_CONSTANTS_ATTRIBUTE_NAME = "modeConstants";
	private static final String MODE_TASTE_VARIATIONS = "modeTasteVariations";
	private final static Logger log = LogManager.getLogger(Person.class);

	private PersonUtils() {
	} // do not instantiate

	@Deprecated // use methods of interface Person
	//yyy there is no such method in the Person interface.  paul, feb'25
	public static Plan createAndAddPlan(Person person, final boolean selected) {
		Plan p = PopulationUtils.createPlan(person);
		person.addPlan(p);
		if (selected) {
			person.setSelectedPlan(p);
		}
		return p;
	}

	public static void removeUnselectedPlans(Person person) {
		for (Iterator<? extends Plan> iter = person.getPlans().iterator(); iter.hasNext(); ) {
			Plan plan = iter.next();
			if (!PersonUtils.isSelected(plan)) {
				iter.remove();
			}
		}
	}

	/**
	 * convenience method for often used demographic attribute
	 */
	public static String getSex(Person person) {
		return (String) person.getAttributes().getAttribute(SEX_ATTRIBUTE);
	}

	/**
	 * convenience method for often used demographic attribute
	 */
	public static Integer getAge(Person person) {
		return (Integer) person.getAttributes().getAttribute(AGE);
	}

	/**
	 * convenience method for often used demographic attribute
	 */
	public static String getLicense(Person person) {
		return (String) person.getAttributes().getAttribute(HAS_LICENSE);
	}

	/**
	 * convenience method for often used demographic attribute
	 */
	public static Double getIncome(Person person) {
		return (Double) person.getAttributes().getAttribute(PERSONAL_INCOME_ATTRIBUTE_NAME);
	}

	/**
	 * convenience method for often used demographic attribute
	 * There is apparently no way to register a Map(String, Double) at ObjectAttributesConverter since all Maps default
	 * to StringStringMapConverter and there is no way to register a StringDoubleMapConverter. Therefore, the personal
	 * scoring mode constants uses a Map(String, String). If this attribute is read often an alternative similar to
	 * PersonVehicles can be considered.
	 */
	@SuppressWarnings("unchecked")
	public static Map<String, String> getModeConstants(Person person) {
		try {

			return (Map<String, String>) person.getAttributes().getAttribute(PERSONAL_SCORING_MODE_CONSTANTS_ATTRIBUTE_NAME);
		} catch (Exception e) {
			log.error("Error retrieving personalScoringModeConstants from attribute " +
				PERSONAL_SCORING_MODE_CONSTANTS_ATTRIBUTE_NAME + ". Should be a Map<String,String>.");
			log.error(e.getMessage());
			throw new RuntimeException(e.getMessage());
		}
	}

	/**
	 * Method to retrieve the taste variations of a person. Returns null if the attribute is not set.
	 * Returns mapping of modes to utility parameter variations. These are not absolute values but relative to the base config.
	 */
	public static Map<String, Map<ModeUtilityParameters.Type, Double>> getModeTasteVariations(Person person) {

		Object attr = person.getAttributes().getAttribute(MODE_TASTE_VARIATIONS);
		if (attr == null) {
			return null;
		}

		String variationsStr = (String) attr;
		Map<String, Map<ModeUtilityParameters.Type, Double>> result = new HashMap<>();

		// Split by semicolon to get each mode-parameter pair
		String[] pairs = variationsStr.split(";");

		for (String pair : pairs) {
			pair = pair.trim();
			if (pair.isEmpty()) continue;

			// Each pair should be in format "mode_parameter=value"
			String[] parts = pair.split("=");
			if (parts.length != 2) {
				log.warn("Invalid taste variation format: {}", pair);
				continue;
			}

			String modeAndParam = parts[0].trim();
			String valueStr = parts[1].trim();

			// Split mode and parameter which are separated by underscore
			int lastUnderscore = modeAndParam.lastIndexOf('-');
			if (lastUnderscore == -1) {
				log.warn("Invalid mode_parameter format: {}", modeAndParam);
				continue;
			}

			String mode = modeAndParam.substring(0, lastUnderscore);
			String paramStr = modeAndParam.substring(lastUnderscore + 1);

			ModeUtilityParameters.Type param = ModeUtilityParameters.Type.valueOf(paramStr);
			double value = Double.parseDouble(valueStr);

			result.computeIfAbsent(mode, k -> new EnumMap<>(ModeUtilityParameters.Type.class)).put(param, value);
		}

		return result;
	}

	/**
	 * Set the taste variations of a persons. The map contains the mode as key and a map of utility parameters and their variation for the person.
	 */
	public static void setModeTasteVariations(Person person, Map<String, Map<ModeUtilityParameters.Type, Double>> modeTasteVariations) {

		if (modeTasteVariations == null) {
			person.getAttributes().removeAttribute(MODE_TASTE_VARIATIONS);
			return;
		}

		StringBuilder sb = new StringBuilder();
		boolean first = true;

		for (Map.Entry<String, Map<ModeUtilityParameters.Type, Double>> modeEntry : modeTasteVariations.entrySet()) {
			String mode = modeEntry.getKey();
			Map<ModeUtilityParameters.Type, Double> paramMap = modeEntry.getValue();

			for (Map.Entry<ModeUtilityParameters.Type, Double> paramEntry : paramMap.entrySet()) {
				if (!first) {
					sb.append(";");
				}
				first = false;

				ModeUtilityParameters.Type param = paramEntry.getKey();
				Double value = paramEntry.getValue();

				sb.append(mode).append("-").append(param.name()).append("=").append(value);
			}
		}

		person.getAttributes().putAttribute(MODE_TASTE_VARIATIONS, sb.toString());
	}

	/**
	 * convenience method for often used demographic attribute
	 */
	public static boolean hasLicense(Person person) {
		return ("yes".equals(getLicense(person))) || ("true".equals(getLicense(person)));
	}

	/**
	 * convenience method for often used demographic attribute
	 */
	public static String getCarAvail(Person person) {
		return (String) person.getAttributes().getAttribute(CAR_AVAIL);
	}

	/**
	 * Car is not available if Person.getLicense gives "no" or if
	 * PersonUtils.getCarAvail returns "never".
	 */
	public static boolean canUseCar(Person person) {
		return !"no".equals(PersonUtils.getLicense(person)) && !"never".equals(PersonUtils.getCarAvail(person));
	}

	/**
	 * convenience method for often used demographic attribute
	 */
	public static Boolean isEmployed(Person person) {
		return (Boolean) person.getAttributes().getAttribute(EMPLOYED);
	}


	/**
	 * convenience method for often used demographic attribute
	 */
	public static void setAge(Person person, final Integer age) {
		if (age != null) {
			person.getCustomAttributes().put(AGE, age);
			person.getAttributes().putAttribute(AGE, age);
		}
	}

	/**
	 * convenience method for often used demographic attribute
	 */
	public static void setSex(Person person, final String sex) {
		if (sex != null) {
			person.getCustomAttributes().put(SEX_ATTRIBUTE, sex);
			person.getAttributes().putAttribute(SEX_ATTRIBUTE, sex);
		}
	}

	/**
	 * convenience method for often used demographic attribute
	 */
	public static void setLicence(Person person, final String licence) {
		if (licence != null) {

			person.getCustomAttributes().put(HAS_LICENSE, licence);
			person.getAttributes().putAttribute(HAS_LICENSE, licence);
		}
	}

	/**
	 * convenience method for often used demographic attribute
	 */
	public static void setCarAvail(Person person, final String carAvail) {
		if (carAvail != null) {
			person.getCustomAttributes().put(CAR_AVAIL, carAvail);
			person.getAttributes().putAttribute(CAR_AVAIL, carAvail);
		}
	}

	/**
	 * convenience method for often used demographic attribute
	 */
	public static void setEmployed(Person person, final Boolean employed) {
		if (employed != null) {
			person.getCustomAttributes().put(EMPLOYED, employed);
			person.getAttributes().putAttribute(EMPLOYED, employed);
		}
	}

	/**
	 * convenience method for often used demographic attribute
	 */
	public static void setIncome(Person person, final double income) {
		person.getCustomAttributes().put(PERSONAL_INCOME_ATTRIBUTE_NAME, income); // deprecated, should not be necessary anymore
		person.getAttributes().putAttribute(PERSONAL_INCOME_ATTRIBUTE_NAME, income);
	}

	public static void setModeConstants(Person person, Map<String, String> mode2scoringConstant) {
		person.getAttributes().putAttribute(PERSONAL_SCORING_MODE_CONSTANTS_ATTRIBUTE_NAME, mode2scoringConstant);
	}

	@Deprecated // yyyy is there a way to use person.getAttributes instead??  kai, nov'16
	public static void addTravelcard(Person person, final String type) {
		if (getTravelcards(person) == null) {
			person.getCustomAttributes().put(TRAVEL_CARDS, new TreeSet<String>());
		}
		if (getTravelcards(person).contains(type)) {
			log.info(person + "[type=" + type + " already exists]");
		} else {
			getTravelcards(person).add(type.intern());
		}
	}

	@SuppressWarnings("unchecked")
	@Deprecated // use PersonAttributes
	public static TreeSet<String> getTravelcards(Person person) {
		return (TreeSet<String>) person.getCustomAttributes().get(TRAVEL_CARDS);
	}

	public static boolean isSelected(Plan plan) {
		return plan.getPerson().getSelectedPlan() == plan;
	}

	/**
	 * Attaches vehicle types to a person, so that the router knows which vehicle to use for which mode and person.
	 *
	 * @param modeToVehicleType mode string mapped to vehicle type ids. The provided map is copied and stored as unmodifiable map.
	 */
	public static void insertVehicleTypesIntoPersonAttributes(Person person, Map<String, Id<VehicleType>> modeToVehicleType) {
		VehicleUtils.insertVehicleTypesIntoPersonAttributes(person, modeToVehicleType);
	}

	/**
	 * Attaches vehicle ids to a person, so that the router knows which vehicle to use for which mode and person.
	 *
	 * @param modeToVehicle mode string mapped to vehicle ids. The provided map is copied and stored as unmodifiable map.
	 *                      If a mode key already exists in the persons's attributes it is overridden. Otherwise, existing
	 *                      and provided values are merged into one map
	 *                      We use PersonVehicle Class in order to have a dedicated PersonVehicleAttributeConverter to/from XML
	 */
	public static void insertVehicleIdsIntoPersonAttributes(Person person, Map<String, Id<Vehicle>> modeToVehicle) {
		VehicleUtils.insertVehicleIdsIntoPersonAttributes(person, modeToVehicle);
	}
}
