package org.matsim.simwrapper;


/**
 * Header section of a {@link Dashboard}.
 */
public final class Header {

	/**
	 * Text to be displayed in the tab.
	 */
	public String tab;
	/**
	 * Title of the dashboard.
	 */
	public String title;
	public String description;

	/**
	 * Fill/stretch dashboard to the full window size instead of scrolling vertically.
	 */
	public Boolean fullScreen;

	/**
	 * Set the dashboard to appear only when a certain file / directory is present.
	 */
	public String triggerPattern;

}
