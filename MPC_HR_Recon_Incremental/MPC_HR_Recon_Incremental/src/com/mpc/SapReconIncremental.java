package com.mpc;

import groovy.lang.GroovyClassLoader;
import groovy.lang.GroovyObject; 
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.logging.*;

/**
 * This class will be used to create a scheduled task in Saviynt to import users from
 * HR source View.
 * 
 * @since 30/04/2020
 * @DevelopedBy Simeio Solutions Ltd
 * @DevelopedFor MPC
 * 
 */

public class SapReconIncremental {

	public static void main(final String[] args) throws IllegalAccessException, InstantiationException, IOException {
		String logfilelocation = "/opt/saviynt/logs/SapReconIncremental.log";
		Logger logger = Logger.getLogger("MyLog");
		FileHandler fh = new FileHandler(logfilelocation, true);
		logger.addHandler(fh);
		SimpleFormatter formatter = new SimpleFormatter();
		fh.setFormatter(formatter);
		logger.info("Initializing HR Incremental sync");
		logger.setLevel(Level.FINE);
		logger.setLevel(Level.WARNING);

		final GroovyClassLoader classLoader = new GroovyClassLoader();
		logger.info("-------Parsing groovy file-------");

		try {
			Class<GroovyObject> groovy = classLoader.parseClass(new File("/opt/saviynt/mpc_hr_sync/sap_reconinc.groovy"));
			logger.info("--------Sap_reconinc.groovy is successfully parsed-----------");
			GroovyObject groovyObj = groovy.newInstance();
			logger.info("--------------invoking the main method-----------");
			groovyObj.invokeMethod("main", new Object[0]);
		} catch (FileNotFoundException e) {
			e.printStackTrace();
			logger.warning("File sap_reconinc.groovy is not found");
		} catch (Exception ex) {
            e.printStackTrace();
            logger.error("Unknown error");
        }
	}
}
