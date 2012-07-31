/*******************************************************************************
 * Copyright (c) 2004, 2010 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.osgi.internal.location;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import org.eclipse.osgi.internal.framework.EquinoxConfiguration;
import org.eclipse.osgi.service.datalocation.Location;
import org.eclipse.osgi.util.NLS;

/**
 * Internal class.
 */
public class BasicLocation implements Location {
	public static final String PROP_OSGI_LOCKING = "osgi.locking"; //$NON-NLS-1$
	private static String DEFAULT_LOCK_FILENAME = ".metadata/.lock"; //$NON-NLS-1$

	static class MockLocker implements Locker {
		/**
		 * @throws IOException  
		 */
		public boolean lock() throws IOException {
			// locking always successful
			return true;
		}

		public boolean isLocked() {
			// this lock is never locked
			return false;
		}

		public void release() {
			// nothing to release
		}

	}

	final private boolean isReadOnly;
	final private URL defaultValue;
	final private String property;
	final private String dataAreaPrefix;
	final private EquinoxConfiguration environmentInfo;
	final private boolean debug;

	private URL location = null;
	private Location parent;

	// locking related fields
	private File lockFile;
	private Locker locker;

	public static Locker createLocker(File lock, String lockMode, boolean debug) {
		if ("none".equals(lockMode)) //$NON-NLS-1$
			return new MockLocker();

		if ("java.io".equals(lockMode)) //$NON-NLS-1$
			return new Locker_JavaIo(lock);

		if ("java.nio".equals(lockMode)) { //$NON-NLS-1$
			return new Locker_JavaNio(lock, debug);
		}

		//	Backup case if an invalid value has been specified
		return new Locker_JavaNio(lock, debug);
	}

	public BasicLocation(String property, URL defaultValue, boolean isReadOnly, String dataAreaPrefix, EquinoxConfiguration environmentInfo) {
		this.property = property;
		this.defaultValue = defaultValue;
		this.isReadOnly = isReadOnly;
		this.dataAreaPrefix = dataAreaPrefix == null ? "" : dataAreaPrefix; //$NON-NLS-1$
		this.environmentInfo = environmentInfo;
		this.debug = environmentInfo.getDebug().DEBUG_LOCATION;
	}

	public boolean allowsDefault() {
		return defaultValue != null;
	}

	public URL getDefault() {
		return defaultValue;
	}

	public synchronized Location getParentLocation() {
		return parent;
	}

	public synchronized URL getURL() {
		if (location == null && defaultValue != null)
			setURL(defaultValue, false);
		return location;
	}

	public synchronized boolean isSet() {
		return location != null;
	}

	public boolean isReadOnly() {
		return isReadOnly;
	}

	/**
	 * @deprecated
	 */
	public boolean setURL(URL value, boolean lock) throws IllegalStateException {
		try {
			return set(value, lock);
		} catch (IOException e) {
			return false;
		}
	}

	public synchronized boolean set(URL value, boolean lock) throws IllegalStateException, IOException {
		return set(value, lock, null);
	}

	public synchronized boolean set(URL value, boolean lock, String lockFilePath) throws IllegalStateException, IOException {
		if (location != null)
			throw new IllegalStateException(EclipseAdaptorMsg.ECLIPSE_CANNOT_CHANGE_LOCATION);
		File file = null;
		if (value.getProtocol().equalsIgnoreCase("file")) { //$NON-NLS-1$
			try {
				String basePath = new File(value.getFile()).getCanonicalPath();
				value = LocationHelper.buildURL("file:" + basePath, true); //$NON-NLS-1$
			} catch (IOException e) {
				// do nothing just use the original value
			}
			if (lockFilePath != null && lockFilePath.length() > 0) {
				File givenLockFile = new File(lockFilePath);
				if (givenLockFile.isAbsolute()) {
					file = givenLockFile;
				} else {
					file = new File(value.getFile(), lockFilePath);
				}
			} else {
				file = new File(value.getFile(), DEFAULT_LOCK_FILENAME);
			}
		}
		lock = lock && !isReadOnly;
		if (lock) {
			if (!lock(file, value))
				return false;
		}
		lockFile = file;
		location = value;
		if (property != null)
			environmentInfo.setConfiguration(property, location.toExternalForm());
		return lock;
	}

	public synchronized void setParent(Location value) {
		parent = value;
	}

	public synchronized boolean lock() throws IOException {
		if (!isSet())
			throw new IOException(EclipseAdaptorMsg.location_notSet);
		return lock(lockFile, location);
	}

	public synchronized boolean isLocked() throws IOException {
		if (!isSet())
			return false;
		return isLocked(lockFile);
	}

	/*
	 * This must be called while holding the synchronization lock for (this)
	 */
	private boolean lock(File lock, URL locationValue) throws IOException {
		if (isReadOnly)
			throw new IOException(NLS.bind(EclipseAdaptorMsg.location_folderReadOnly, lock));
		if (lock == null) {
			if (locationValue != null && !"file".equalsIgnoreCase(locationValue.getProtocol())) //$NON-NLS-1$
				throw new IOException(NLS.bind(EclipseAdaptorMsg.location_notFileProtocol, locationValue));
			throw new IllegalStateException(EclipseAdaptorMsg.location_noLockFile); // this is really unexpected
		}
		if (isLocked())
			return false;
		File parentFile = new File(lock.getParent());
		if (!parentFile.exists())
			if (!parentFile.mkdirs())
				throw new IOException(NLS.bind(EclipseAdaptorMsg.location_folderReadOnly, parentFile));

		setLocker(lock);
		if (locker == null)
			return true;
		boolean locked = false;
		try {
			locked = locker.lock();
			return locked;
		} finally {
			if (!locked)
				locker = null;
		}
	}

	/*
	 * This must be called while holding the synchronization lock for (this)
	 */
	private boolean isLocked(File lock) throws IOException {
		if (lock == null || isReadOnly)
			return true;
		if (!lock.exists())
			return false;
		setLocker(lock);
		return locker.isLocked();
	}

	/*
	 * This must be called while holding the synchronization lock for (this)
	 */
	private void setLocker(File lock) {
		if (locker != null)
			return;
		String lockMode = environmentInfo.getConfiguration(PROP_OSGI_LOCKING);
		locker = createLocker(lock, lockMode, debug);
	}

	public synchronized void release() {
		if (locker != null)
			locker.release();
	}

	public Location createLocation(Location parentLocation, URL defaultLocation, boolean readonly) {
		BasicLocation result = new BasicLocation(null, defaultLocation, readonly, dataAreaPrefix, environmentInfo);
		result.setParent(parentLocation);
		return result;
	}

	public URL getDataArea(String filename) throws IOException {
		URL base = getURL();
		if (base == null)
			throw new IOException(EclipseAdaptorMsg.location_notSet);
		String prefix = base.toExternalForm();
		if (prefix.length() > 0 && prefix.charAt(prefix.length() - 1) != '/')
			prefix += '/';
		filename = filename.replace('\\', '/');
		if (filename.length() > 0 && filename.charAt(0) == '/')
			filename.substring(1);
		return new URL(prefix + dataAreaPrefix + filename);
	}
}
