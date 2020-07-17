package com.mpc;

import groovy.lang.GroovyClassLoader;
import groovy.lang.GroovyObject; 
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * This class will run as a scheduled task in Saviynt to import users from the HR source view to the staging table. The actual process
 * runs through a Groovy script
 * 
 * @since 07/06/2020
 * @DevelopedBy Wes Vollmar (WZS)
 */
public class SapRecon {

	/*
	* Main method to parse and execute the groovy script
	*
	* @args - 
    *   [0]: Full/Incremental (default incremental) 
	*   [1]: Days to provision before start (default 14)
	*/
	public static void main(Object[] args) throws IllegalAccessException, InstantiationException, IOException {
		final GroovyClassLoader classLoader = new GroovyClassLoader();

		try {
			Class<GroovyObject> groovy = classLoader.parseClass(new File("/opt/saviynt/mpc_hr_sync/SapRecon.groovy"));
			GroovyObject groovyObj = groovy.newInstance();
			groovyObj.invokeMethod("main", args);
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (Exception ex) {
            ex.printStackTrace();
        }
	}
}
