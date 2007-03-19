/******************************************************************************
 * Copyright (c) 2003, 2004 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    IBM Corporation - initial API and implementation 
 ****************************************************************************/


package org.eclipse.emf.validation.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.validation.internal.EMFModelValidationDebugOptions;
import org.eclipse.emf.validation.internal.EMFModelValidationPlugin;
import org.eclipse.emf.validation.internal.EMFModelValidationStatusCodes;
import org.eclipse.emf.validation.internal.service.BatchValidator;
import org.eclipse.emf.validation.internal.service.ConstraintCache;
import org.eclipse.emf.validation.internal.service.IProviderDescriptor;
import org.eclipse.emf.validation.internal.service.IProviderOperation;
import org.eclipse.emf.validation.internal.service.IProviderOperationExecutor;
import org.eclipse.emf.validation.internal.service.LiveValidator;
import org.eclipse.emf.validation.internal.service.ProviderDescriptor;
import org.eclipse.emf.validation.internal.util.Log;
import org.eclipse.emf.validation.internal.util.Trace;
import org.eclipse.emf.validation.model.EvaluationMode;
import org.eclipse.emf.validation.model.IModelConstraint;
import org.eclipse.emf.validation.util.XmlConfig;

/**
 * <p>
 * The Model Validation Service makes constraints and validators available to
 * the client application.  An application obtains validators from the service
 * and requests validation whenever it is appropriate, according to the
 * application's mapping of the {@link EvaluationMode} triggers.
 * </p>
 * <p>
 * The <code>ModelValidationService</code> delegates the retrieval of
 * constraints to any number of {@link IModelConstraintProvider}
 * implementations registered by other plug-ins via the
 * <tt>org.eclipse.emf.validation.constraintProvider</tt> extension point.
 * </p>
 * <p>
 * The validation service uses the meta-data associated with registered
 * providers to determine which ones can provide constraints for a specific
 * EMF object according to the meta-model URI namespace and the evaluation
 * context.  This allows the
 * service to delay instantiating providers (and, hence, loading plug-ins) until
 * it is absolutely necessary to do so.
 * </p>
 * 
 * @see #newValidator(EvaluationMode)
 * @see IValidator
 * @see org.eclipse.emf.validation.model.IModelConstraint
 * 
 * @author Christian W. Damus (cdamus)
 */
public class ModelValidationService {
	private static final ModelValidationService instance = new ModelValidationService();
	
	private final Collection constraintProviders = new java.util.HashSet();
	
	// latch to control multiple invocations of loadXmlConstraintDefinitions()
	private boolean xmlConstraintDeclarationsLoaded = false;
	
	private volatile IValidationListener[] listeners;
	
	/** The one and only constraint provider that implements the cache. */
	private ConstraintCache constraintCache;
	
	/**
	 * Cannot be instantiated by clients.
	 */
	private ModelValidationService() {
		super();
	}

	/**
	 * Obtains the instance of this class.
	 * 
	 * @return the <em>Singleton</em> instance
	 */
	public static ModelValidationService getInstance() {
		return instance;
	}

	/**
	 * <p>
	 * Creates a new validator object that the client can use to validate
	 * EMF objects, notifications, or features, according to the value of the
	 * specified evaluation <code>mode</code>.
	 * </p>
	 * <p>
	 * The resulting validator may be retained as long as it is needed, and
	 * reused any number of times.  Each validator has its own separate state.
	 * </p>
	 * @param mode the evaluation mode for which to create a new validator.
	 *       Must not be <code>null</code> or {@link EvaluationMode#NULL}
	 * @return a new validator
	 * @throws IllegalArgumentException if the <code>mode</code> is not a
	 *       valid evaluation mode
	 */
	public IValidator newValidator(EvaluationMode mode) {
		assert mode != null && !mode.isNull();
		
		IProviderOperationExecutor executor = new IProviderOperationExecutor() {
			public void execute(IProviderOperation op) {
				ModelValidationService.this.execute(op);
			}};
		
		if (mode == EvaluationMode.BATCH) {
			return new BatchValidator(executor);
		} else if (mode == EvaluationMode.LIVE) {
			return new LiveValidator(executor);
		} else {
			throw new IllegalArgumentException();
		}
	}
	
	/**
	 * Adds a new <code>listener</code> to receive validation events.  This
	 * method has no effect if the <code>listener</code> is already registered.
	 * 
	 * @param listener a new validation listener
	 */
	public synchronized void addValidationListener(IValidationListener listener) {
		if (indexOf(listener) < 0) {
			if (listeners == null) {
				listeners = new IValidationListener[] {listener};
			} else {
				IValidationListener[] newListeners =
					new IValidationListener[listeners.length + 1];
				
				System.arraycopy(listeners, 0, newListeners, 0, listeners.length);
				newListeners[listeners.length] = listener;
				listeners = newListeners;
			}
			
			if (Trace.shouldTrace(EMFModelValidationDebugOptions.LISTENERS)) {
				Trace.trace(
						EMFModelValidationDebugOptions.LISTENERS,
						"Registered listener: " + listener.getClass().getName()); //$NON-NLS-1$
			}
		}
	}
	
	/**
	 * Removes a <code>listener</code> from the service.  This method
	 * has no effect if the <code>listener</code> is not currently registered.
	 * 
	 * @param listener a validation listener
	 */
	public synchronized void removeValidationListener(IValidationListener listener) {
		int index = indexOf(listener);
		
		if (index >= 0) {
			IValidationListener[] newListeners =
				new IValidationListener[listeners.length - 1];
			
			System.arraycopy(listeners, 0, newListeners, 0, index);
			System.arraycopy(listeners, index + 1, newListeners, index, listeners.length - index - 1);
			listeners = newListeners;
			
			if (Trace.shouldTrace(EMFModelValidationDebugOptions.LISTENERS)) {
				Trace.trace(
						EMFModelValidationDebugOptions.LISTENERS,
						"Deregistered listener: " + listener.getClass().getName()); //$NON-NLS-1$
			}
		}
	}
	
	/**
	 * Computes the index of a specified <code>listener</code> in the array
	 * of registered listeners.
	 * 
	 * @param listener a listener
	 * @return the <code>listener</code>'s index, or -1 if it is not in my list
	 */
	private int indexOf(IValidationListener listener) {
		int result = -1;
		if (listeners != null) {
			for (int i = 0; i < listeners.length; i++) {
				if (listeners[i] == listener) {
					result = i;
					break;
				}
			}
		}
		
		return result;
	}
	
	/**
	 * Broadcasts the specified <code>event</code> to all listeners.  This
	 * method is used internally by validators to send notifications when they
	 * perform validation, but may also be used by clients to simulate
	 * validation occurrences.
	 * 
	 * @param event a validation event to broadcast
	 */
	public void broadcastValidationEvent(ValidationEvent event) {
		// Check if listeners exist
		if (listeners == null)
			return;
		
		IValidationListener[] array = listeners; // copy the reference
		
		for (int i = 0; i < array.length; i++) {
			try {
				array[i].validationOccurred(event);
			} catch (Exception e) {
				Trace.catching(getClass(), "broadcastValidationEvent", e); //$NON-NLS-1$
				
				if (Trace.shouldTrace(EMFModelValidationDebugOptions.LISTENERS)) {
					Trace.trace(
							EMFModelValidationDebugOptions.LISTENERS,
							"Uncaught exception in listener: " + array[i].getClass().getName()); //$NON-NLS-1$
				}
				
				Log.l7dWarning(
					EMFModelValidationStatusCodes.LISTENER_UNCAUGHT_EXCEPTION,
					EMFModelValidationStatusCodes.LISTENER_UNCAUGHT_EXCEPTION_MSG,
					e);
			}
		}
	}

	/**
	 * <p>
	 * Configures my listeners from the Eclipse configuration
	 * <code>elements</code> representing implementations of my extension point.
	 * </p>
	 * <p>
	 * <b>NOTE</b> that this method should only be called by the EMF Model
	 * Validation Plug-in, not by any client code!
	 * </p>
	 * 
	 * @param elements 
	 */
	public void configureListeners(IConfigurationElement[] elements) {
		assert elements != null;

		for (int i = 0; i < elements.length; i++) {
			if (elements[i].getName().equals("listener")) { //$NON-NLS-1$
				try {
					addValidationListener(new LazyListener(elements[i]));
				} catch (CoreException e) {
					Trace.catching(getClass(), "configureListeners()", e); //$NON-NLS-1$
					
					Log.log(e.getStatus());
				}
			}
		}
	}

	/**
	 * <p>
	 * Configures my providers from the Eclipse configuration
	 * <code>elements</code> representing implementations of my extension point.
	 * </p>
	 * <p>
	 * <b>NOTE</b> that this method should only be called by the EMF Model
	 * Validation Plug-in, not by any client code!
	 * </p>
	 * 
	 * @param elements 
	 */
	public void configureProviders(IConfigurationElement[] elements) {
		assert elements != null;

		constraintCache = new ConstraintCache();
		
		Collection providers = getProviders();

		// include the cache in my collection of providers
		providers.add(constraintCache.getDescriptor());
		
		for (int i = 0; i < elements.length; i++) {
			if (elements[i].getName().equals(XmlConfig.E_CONSTRAINT_PROVIDER)) {
				try {
					IProviderDescriptor descriptor =
						new ProviderDescriptor(elements[i]);
					
					if (descriptor.isCacheEnabled()) {
						constraintCache.addProvider(descriptor);
		
						if (Trace.shouldTrace(EMFModelValidationDebugOptions.PROVIDERS)) {
							Trace.trace(
									EMFModelValidationDebugOptions.PROVIDERS,
									"Added provider to cache: " + descriptor); //$NON-NLS-1$
						}
					} else {
						providers.add(descriptor);
		
						if (Trace.shouldTrace(EMFModelValidationDebugOptions.PROVIDERS)) {
							Trace.trace(
									EMFModelValidationDebugOptions.PROVIDERS,
									"Loaded uncacheable provider: " + descriptor); //$NON-NLS-1$
						}
					}
				} catch (CoreException e) {
					Trace.catching(getClass(), "configureProviders()", e); //$NON-NLS-1$
					
					Log.log(e.getStatus());
				}
			}
		}
	}
	
	/**
	 * Replaces a constraint in the cache with an alternative implementation.
	 * This must only be invoked by constraint providers, and then only when the
	 * provider can ensure that the new constraint implementation's semantics
	 * are compatible with the old.
	 * 
	 * @param oldConstraint the constraint to be replaced in the cache
	 * @param newConstraint the new constraint to replace it
	 */
	public void replaceInCache(IModelConstraint oldConstraint, IModelConstraint newConstraint) {
		constraintCache.replace(oldConstraint, newConstraint);
	}

	/**
	 * Obtains the providers that are registered on my extension point.
	 * 
	 * @return a collection of {@link ProviderDescriptor}s
	 */
	private Collection getProviders() {
		return constraintProviders;
	}

	/**
	 * Executes the specified <code>operation</code> on all of my providers.
	 * 
	 * @param operation the operation to execute
	 */
	private void execute(IProviderOperation operation) {
		for (Iterator iter = getProviders().iterator(); iter.hasNext(); ) {
			IProviderDescriptor next = (IProviderDescriptor)iter.next();

			if (next.provides(operation)) {
				try {
					operation.execute(next.getProvider());
				} catch (RuntimeException e) {
					Trace.catching(getClass(), "execute", e); //$NON-NLS-1$
					Log.l7dWarning(
							EMFModelValidationStatusCodes.PROVIDER_FAILURE,
							EMFModelValidationStatusCodes.PROVIDER_FAILURE_MSG,
							e);
					
					iter.remove();  // don't try the offending provider, again
				}
			}
		}
	}

	/**
	 * <p>
	 * Loads all available XML-declared constraint descriptors.  This is not
	 * a very heavy-weight operation, as it does not require the loading of
	 * any plug-ins or even the instantantiation of any constraints.  It only
	 * loads the constraint descriptors that are statically declared in XML.
	 * </p>
	 * <p>
	 * Subsequent invocations of this method have no effect.
	 * </p>
	 * <b>NOTE</b> that this method should only be called by the EMF Model
	 * Validation Plug-in, not by any client code!
	 * </p>
	 */
	public void loadXmlConstraintDeclarations() {
		if (!xmlConstraintDeclarationsLoaded) {
			xmlConstraintDeclarationsLoaded = true;
			
			loadXmlConstraintDeclarations(getProviders());
		}
	}
	
	/**
	 * Helper method to load the constraint declarations frm the specified
	 * <code>providers</code>.  Note that only the
	 * {@link IProviderDescriptor#isXmlProvider XML-based} providers are
	 * consulted.
	 * 
	 * @param providers the available providers
	 */
	private void loadXmlConstraintDeclarations(Collection providers) {
		for (Iterator iter = providers.iterator(); iter.hasNext();) {
			IProviderDescriptor next = (IProviderDescriptor)iter.next();
			
			if (next.isXmlProvider()) {
				// the initialization of this provider is not very expensive
				//    and is guaranteed not to load any other plug-ins
				next.getProvider();
				
				// nothing more to do.  I only needed to initialize the
				//   provider in order for the categories to find their
				//   constraints
			} else if (next.isCache()) {
				loadXmlConstraintDeclarations(
						((ConstraintCache)next.getProvider()).getProviders());
			}
		}
	}
	
	/**
	 * Finds the {@link EClass} having the specified name within the namespace
	 * indicated by the URI.  The class name may optionally be fully qualified
	 * (prefixed by its full package name) to support constraint providers that
	 * need to disambiguate like-named classes in different EPackages.
	 * 
	 * @param namespaceUri the provider-specified namespace URI of the EPackage
	 * @param className the class name.  May be a simple name within the
	 *     package namespace indicated by <code>namespaceUri</code> or a
	 *     fully-qualified class name
	 * @return the corresponding EMF class object, or <code>null</code> if it
	 *    could not be found
	 */
	public static EClass findClass(String namespaceUri, String className) {
		EClass result = null;

		EPackage epackage = findPackage(namespaceUri);

		if (epackage != null) {
			EClassifier classifier = null;
			
			List packageNames = parsePackageNames(className);
			if (packageNames == null) {
				classifier = epackage.getEClassifier(className);
			} else if (packageHasName(epackage, packageNames)) {
				// strip off the package prefix from the class name
				className = className.substring(className.lastIndexOf('.') + 1); // known BMP code point
				
				// look up the simple class name in this package
				classifier = epackage.getEClassifier(className);
			}
			
			if (classifier instanceof EClass) {
				// note that null is not an instance of any Java type!
				result = (EClass)classifier;
			}
		}

		return result;
	}

	/**
	 * Helper method to find an EMF package when looking up a class.
	 * 
	 * @param namespaceUri the provider-specified namespace URI of the EPackage
	 * @return the package, or <code>null</code> if it could not be found
	 * 
	 * @see #findClass(String, String)
	 */
	private static EPackage findPackage(String namespaceUri) {
		Map registry = org.eclipse.emf.ecore.EPackage.Registry.INSTANCE;
		
		Object result = registry.get(namespaceUri);
		
		if (result instanceof EPackage.Descriptor) {
			// force initialization of the package
			result = ((EPackage.Descriptor)result).getEPackage();
		}
		
		return (EPackage)result;
	}
	
	/**
	 * Helper method that computes the package names in a qualified class name
	 * in reverse (right-to-left) order, and returns them as a list.  If the
	 * class name is not qualified, then the result is <code>null</code>.
	 * 
	 * @param qualifiedClassName a possibly package-qualified class name
	 * @return the package names in right-to-left order, as a list of
	 *    strings, or <code>null</code> if the class name is not qualified
	 */
	private static List parsePackageNames(String qualifiedClassName) {
		List result = null;
		int end = qualifiedClassName.lastIndexOf('.'); // known BMP code point
		
		if (end >= 0) {
			result = new java.util.ArrayList();
			
			// skip the class name part and collect other parts in
			//  reverse order
			do {
				int start = qualifiedClassName.lastIndexOf('.', end - 1); // known BMP code point
				result.add(qualifiedClassName.substring(start + 1, end));
				
				end = start;
			} while (end >= 0);
		}
		
		return result;
	}
	
	/**
	 * Helper method to determine whether a package has the specified qualified
	 * <code>name</code>.  The name is a list of strings in right-to-left
	 * order (bottom to top of package containment hierarchy) obtained from the
	 * {@link #parsePackageNames(String)} method.
	 * 
	 * @param epackage an EMF package
	 * @param name a qualified package name, as a list of strings, ordered from
	 *    right to left
	 * @return <code>true</code> if the <code>names</code> match the name of
	 *    the package from bottom to top of the containment hierarchy;
	 *    <code>false</code>, otherwise
	 */
	private static boolean packageHasName(EPackage epackage, List name) {
		boolean result = true;
		EPackage pkg = epackage;
		Iterator iter = name.iterator();
		
		while (result && iter.hasNext() && (pkg != null)) {
			result = iter.next().equals(pkg.getName());
			
			pkg = pkg.getESuperPackage();
		}
		
		// if all available names matched, make sure that there are the same
		//   number of names as packages!
		result = result && !iter.hasNext() && (pkg == null);
		
		return result;
	}
	
	/**
	 * Implementation of a lazily-initialized validation listener registered
	 * on the <tt>validationListener</tt> extension point.  On any callback, the
	 * lazy listener is replaced by the real one so that it will not be
	 * invoked a second time.
	 *
	 * @author Christian W. Damus (cdamus)
	 */
	private final class LazyListener implements IValidationListener {
		private final IConfigurationElement config;
		private List registeredClientContexts = null;
		private IValidationListener validationListener = null;
		
		private static final String E_CLIENT_CONTEXT = "clientContext"; //$NON-NLS-1$
		private static final String A_CLIENT_CONTEXT_ID = "id"; //$NON-NLS-1$
		
		LazyListener(IConfigurationElement config) throws CoreException {
			assert config != null;
			this.config = config;
		}
		
		/**
		 * Replaces me in the service's listener list with the real
		 * listener.
		 * 
		 * @return the real listener
		 */
		private IValidationListener replaceMe() throws CoreException {
			IValidationListener result = null;

			result = (IValidationListener)
				config.createExecutableExtension(XmlConfig.A_CLASS);
			
			int index = indexOf(this);
			listeners[index] = result;
			
			return result;
		}
		
		public void validationOccurred(ValidationEvent event) {
			if (registeredClientContexts == null) {
				IConfigurationElement[] children = config.getChildren(E_CLIENT_CONTEXT);
				
				// Probably a small number of registered client contexts.
				registeredClientContexts = new ArrayList(3);
				
				for (int i=0; i<children.length; i++) {
					registeredClientContexts.add(children[i].getAttribute(A_CLIENT_CONTEXT_ID));
				}
			}
			
			// If they have no registered client contexts then they are
			//  a "universal" listener that will receive all events.
			if (registeredClientContexts.size() == 0) {
				try {
					IValidationListener realListener = replaceMe();
					realListener.validationOccurred(event);	
				} catch (Exception e) {
					Trace.catching(getClass(), "validationOccurred", e); //$NON-NLS-1$
					Log.log(IStatus.ERROR,
						EMFModelValidationStatusCodes.LISTENER_UNCAUGHT_EXCEPTION,
						EMFModelValidationPlugin.getMessage(
							EMFModelValidationStatusCodes.LISTENER_UNCAUGHT_EXCEPTION_MSG,
							new Object[] {config.getDeclaringExtension().getUniqueIdentifier()}),
						e);
					removeValidationListener(this);
				}
				return;
			}
			
			// Otherwise, we will delay the loading of this listener until
			//  we are certain that they are interested in listening.
			for (Iterator i = registeredClientContexts.iterator(); i.hasNext();) {
				String clientContextId = (String)i.next();
				
				if (event.getClientContextIds().contains(clientContextId)) {
					try {
						if (validationListener == null) {
							IValidationListener listener = (IValidationListener)
									config.createExecutableExtension(XmlConfig.A_CLASS);
							validationListener = listener;
						}
						validationListener.validationOccurred(event);
					} catch (Exception e) {
						Trace.catching(getClass(), "validationOccurred", e); //$NON-NLS-1$
						Log.log(IStatus.ERROR,
							EMFModelValidationStatusCodes.LISTENER_UNCAUGHT_EXCEPTION,
							EMFModelValidationPlugin.getMessage(
								EMFModelValidationStatusCodes.LISTENER_UNCAUGHT_EXCEPTION_MSG,
								new Object[] {config.getDeclaringExtension().getUniqueIdentifier()}),
							e);
						removeValidationListener(this);
					}
					return;
				}
			}
		}
	}
}
