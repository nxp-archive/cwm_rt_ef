/*******************************************************************************
 * Copyright (c) 2006, 2012 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/

package org.eclipse.osgi.internal.log;

import java.io.File;
import java.io.Writer;
import java.util.Dictionary;
import java.util.Hashtable;
import org.eclipse.core.runtime.adaptor.EclipseStarter;
import org.eclipse.equinox.log.internal.LogServiceManager;
import org.eclipse.osgi.framework.log.FrameworkLog;
import org.eclipse.osgi.framework.log.FrameworkLogEntry;
import org.eclipse.osgi.internal.baseadaptor.AdaptorUtil;
import org.eclipse.osgi.internal.framework.EquinoxConfiguration;
import org.eclipse.osgi.service.datalocation.Location;
import org.osgi.framework.*;

public class EquinoxLogServices {
	static final String EQUINOX_LOGGER_NAME = "org.eclipse.equinox.logger"; //$NON-NLS-1$
	static final String PERF_LOGGER_NAME = "org.eclipse.performance.logger"; //$NON-NLS-1$
	private static final String PROP_LOG_ENABLED = "eclipse.log.enabled"; //$NON-NLS-1$

	// The eclipse log file extension */
	private static final String LOG_EXT = ".log"; //$NON-NLS-1$
	private final LogServiceManager logServiceManager;
	private final EquinoxLogFactory eclipseLogFactory;
	private final EquinoxLogWriter logWriter;
	private final EquinoxLogWriter perfWriter;
	private final FrameworkLog rootFrameworkLog;

	public EquinoxLogServices(EquinoxConfiguration environmentInfo, Location configuration) {
		String logFileProp = environmentInfo.getConfiguration(EclipseStarter.PROP_LOGFILE);
		boolean enabled = "true".equals(environmentInfo.getConfiguration(PROP_LOG_ENABLED, "true")); //$NON-NLS-1$ //$NON-NLS-2$
		if (logFileProp != null) {
			logWriter = new EquinoxLogWriter(new File(logFileProp), EQUINOX_LOGGER_NAME, enabled, environmentInfo);
		} else {
			File configAreaDirectory = null;
			if (configuration != null)
				// TODO assumes the URL is a file: url
				configAreaDirectory = new File(configuration.getURL().getFile());

			if (configAreaDirectory != null) {
				String logFileName = Long.toString(System.currentTimeMillis()) + EquinoxLogServices.LOG_EXT;
				File logFile = new File(configAreaDirectory, logFileName);
				environmentInfo.setConfiguration(EclipseStarter.PROP_LOGFILE, logFile.getAbsolutePath());
				logWriter = new EquinoxLogWriter(logFile, EQUINOX_LOGGER_NAME, enabled, environmentInfo);
			} else
				logWriter = new EquinoxLogWriter((Writer) null, EQUINOX_LOGGER_NAME, enabled, environmentInfo);
		}

		File logFile = logWriter.getFile();
		if (logFile != null) {
			File perfLogFile = new File(logFile.getParentFile(), "performance.log"); //$NON-NLS-1$
			perfWriter = new EquinoxLogWriter(perfLogFile, PERF_LOGGER_NAME, true, environmentInfo);
		} else {
			perfWriter = new EquinoxLogWriter((Writer) null, PERF_LOGGER_NAME, true, environmentInfo);
		}
		if ("true".equals(environmentInfo.getConfiguration(EclipseStarter.PROP_CONSOLE_LOG))) //$NON-NLS-1$
			logWriter.setConsoleLog(true);
		logServiceManager = new LogServiceManager(logWriter, perfWriter);
		eclipseLogFactory = new EquinoxLogFactory(logWriter, logServiceManager);
		rootFrameworkLog = eclipseLogFactory.createFrameworkLog(null, logWriter);
	}

	private ServiceRegistration<?> frameworkLogReg;
	private ServiceRegistration<?> perfLogReg;

	/**
	 * @throws BundleException  
	 */
	public void start(BundleContext context) throws BundleException {
		logServiceManager.start(context);
		frameworkLogReg = AdaptorUtil.register(FrameworkLog.class.getName(), eclipseLogFactory, context);
		perfLogReg = registerPerformanceLog(context);
	}

	/**
	 * @throws BundleException  
	 */
	public void stop(BundleContext context) throws BundleException {
		frameworkLogReg.unregister();
		perfLogReg.unregister();
		logServiceManager.stop(context);
	}

	public FrameworkLog getFrameworkLog() {
		return rootFrameworkLog;
	}

	private ServiceRegistration<?> registerPerformanceLog(BundleContext context) {
		Object service = createPerformanceLog(context.getBundle());
		String serviceName = FrameworkLog.class.getName();
		Dictionary<String, Object> serviceProperties = new Hashtable<String, Object>(7);
		Dictionary<String, String> headers = context.getBundle().getHeaders();

		serviceProperties.put(Constants.SERVICE_VENDOR, headers.get(Constants.BUNDLE_VENDOR));
		serviceProperties.put(Constants.SERVICE_RANKING, new Integer(Integer.MIN_VALUE));
		serviceProperties.put(Constants.SERVICE_PID, context.getBundle().getBundleId() + '.' + service.getClass().getName());
		serviceProperties.put(FrameworkLog.SERVICE_PERFORMANCE, Boolean.TRUE.toString());

		return context.registerService(serviceName, service, serviceProperties);
	}

	private FrameworkLog createPerformanceLog(Bundle systemBundle) {
		return eclipseLogFactory.createFrameworkLog(systemBundle, perfWriter);
	}

	public void log(String entry, int severity, String message, Throwable throwable) {
		log(entry, severity, message, throwable, null);
	}

	public void log(String entry, int severity, String message, Throwable throwable, FrameworkLogEntry[] children) {
		getFrameworkLog().log(new FrameworkLogEntry(entry, severity, 0, message, 0, throwable, children));
	}
}
