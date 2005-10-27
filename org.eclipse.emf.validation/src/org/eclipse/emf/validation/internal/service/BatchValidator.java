/******************************************************************************
 * Copyright (c) 2003, 2005 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    IBM Corporation - initial API and implementation 
 ****************************************************************************/


package org.eclipse.emf.validation.internal.service;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.SubProgressMonitor;
import org.eclipse.emf.ecore.EObject;

import org.eclipse.emf.validation.internal.EMFModelValidationDebugOptions;
import org.eclipse.emf.validation.internal.util.Trace;
import org.eclipse.emf.validation.model.EvaluationMode;
import org.eclipse.emf.validation.service.IBatchValidator;
import org.eclipse.emf.validation.service.ITraversalStrategy;

/**
 * Basic implementation of the {@link IBatchValidator} interface.
 * Ensures that, in cases of multiple selection where recursion is desired,
 * validation is not repeated on selected elements that are contained in other
 * selected elements.
 *
 * @author Christian W. Damus (cdamus)
 */
public class BatchValidator extends AbstractValidator implements IBatchValidator {
	private boolean includeLiveConstraints = false;
	private IProgressMonitor progressMonitor = null;
	
	private ITraversalStrategy traversalStrategy =
		new DefaultRecursiveTraversalStrategy();
	
	/**
	 * Initializes me with the operation <code>executor</code> that I use to
	 * execute provider operations.
	 * 
	 * @param executor used by me to execute operations (must not be
	 *      <code>null</code>)
	 */
	public BatchValidator(IProviderOperationExecutor executor) {
		super(EvaluationMode.BATCH, executor);
	}

	/* (non-Javadoc)
	 * Implements the inherited method.
	 */
	public boolean isIncludeLiveConstraints() {
		return includeLiveConstraints;
	}

	/* (non-Javadoc)
	 * Implements the inherited method.
	 */
	public void setIncludeLiveConstraints(boolean includeLiveConstraints) {
		this.includeLiveConstraints = includeLiveConstraints;
	}
	
	/* (non-Javadoc)
	 * Implements the inherited method.
	 */
	public ITraversalStrategy getDefaultTraversalStrategy() {
		return new DefaultRecursiveTraversalStrategy();
	}
	
	/* (non-Javadoc)
	 * Implements the inherited method.
	 */
	public ITraversalStrategy getTraversalStrategy() {
		return traversalStrategy;
	}
	
	/* (non-Javadoc)
	 * Implements the inherited method.
	 */
	public void setTraversalStrategy(ITraversalStrategy strategy) {
		if (strategy == null) {
			throw new IllegalArgumentException("strategy is null"); //$NON-NLS-1$
		}

		this.traversalStrategy = strategy;
	}

	/* (non-Javadoc)
	 * Implements the inherited method.
	 */
	public IStatus validate(EObject eObject, IProgressMonitor monitor) {
		IStatus result;
		
		progressMonitor = monitor;
		
		result = validate(eObject);
		
		return result;
	}

	/* (non-Javadoc)
	 * Implements the inherited method.
	 */
	public IStatus validate(Collection objects, IProgressMonitor monitor) {
		IStatus result;
		
		progressMonitor = monitor;
		
		result = validate(objects);
		
		return result;
	}

	/* (non-Javadoc)
	 * Implements the inherited method.
	 */
	protected Collection doValidate(Collection objects, Set clientContexts) {
		List result = new java.util.ArrayList(64);  // anticipate large scale
		
		GetBatchConstraintsOperation operation =
			new GetBatchConstraintsOperation(!isIncludeLiveConstraints());
		AbstractValidationContext ctx = operation.getContext();
		ctx.setReportSuccesses(isReportSuccesses());
		
		validate(getTraversalStrategy(), result, ctx, objects, operation, clientContexts);
		
		return result;
	}
	
	/**
	 * Helper method for validation of any number of objects, using the
	 * specified <code>traversal</code> strategy.
	 * 
	 * @param traversal the traversal strategy to employ
	 * @param evaluationResults the evaluation results that are being
	 *     accumulated recursively
	 * @param ctx context for evaluation
	 * @param clientContexts 
	 * @param eObjects a collection of {@link EObject}s to validate
	 * @param the operation to reuse for getting constraints
	 * @param (output) the set of client contexts to be updated with all of the
	 *         encountered contexts while performing validation.
	 */
	private void validate(
			ITraversalStrategy traversal,
			List evaluationResults,
			AbstractValidationContext ctx,
			Collection objects,
			GetBatchConstraintsOperation operation, 
			Set clientContexts) {
		
		IProgressMonitor monitor = progressMonitor;
		if (monitor == null) {
			monitor = new NullProgressMonitor();
		}
		
		traversal.startTraversal(objects, monitor);
		boolean firstElement = true;
		
		try {
			while (traversal.hasNext()) {
				if (monitor.isCanceled()) {
					break;
				}
		
				boolean recomputeClients = firstElement
					|| traversal.isClientContextChanged();
				
				final EObject next = traversal.next();

				if (recomputeClients) {
					Collection contexts = ClientContextManager.getInstance()
							.getClientContextsFor(next);
					ctx.setClientContexts(contexts);
					clientContexts.addAll(contexts);
				}
				
				traversal.elementValidated(
					next,
					validate(
						ctx,
						next,
						operation,
						evaluationResults));
				
				firstElement = false;
			}
		} catch (OperationCanceledException e) {
			// a constraint has requested cancellation of the validation
			//    operation.  Honour that request and propagate the exception
			monitor.setCanceled(true);
			throw e;
		} finally {
			if (!monitor.isCanceled()) {
				monitor.done();
			}

			progressMonitor = null;
		}
	}

	/**
	 * Helper method for validation of a single object.
	 * 
	 * @param ctx the context within which to validate the <code>eObject</code>
	 * @param eObject the EMF object to validate
	 * @param the operation to reuse for getting constraints
	 * @param results list of {@link IStatus} results of constraint evaluations
	 * 
	 * @return a summary status of the <code>eObject</code>'s validation
	 */
	private IStatus validate(
			AbstractValidationContext ctx,
			EObject eObject,
			GetBatchConstraintsOperation operation,
			List results) {
		if (Trace.shouldTraceEntering(EMFModelValidationDebugOptions.PROVIDERS)) {
			Trace.entering(getClass(), "validate", //$NON-NLS-1$
					new Object[] {eObject});
		}

		operation.setTarget(eObject);
		
		execute(operation);
		
		IStatus result = evaluateConstraints(ctx, results);

		if (Trace.shouldTraceExiting(EMFModelValidationDebugOptions.PROVIDERS)) {
			Trace.exiting(
				getClass(),
				"validate", //$NON-NLS-1$
				result);
		}
		
		return result;
	}
	
	private static class DefaultRecursiveTraversalStrategy implements ITraversalStrategy {
		private Map delegates;
		private Iterator delegateIterator;
		private ITraversalStrategy current;
		
		/* (non-Javadoc)
		 * Redefines/Implements/Extends the inherited method.
		 */
		public void startTraversal(Collection traversalRoots, IProgressMonitor monitor) {
			delegates = initDelegates(traversalRoots);
			
			monitor.beginTask("", delegates.size() * 1024); //$NON-NLS-1$
			
			for (Iterator iter = delegates.entrySet().iterator(); iter.hasNext();) {
				Map.Entry next = (Map.Entry)iter.next();
				
				SubProgressMonitor sub = new SubProgressMonitor(
					monitor,
					1024,
					SubProgressMonitor.SUPPRESS_SUBTASK_LABEL);
				((ITraversalStrategy)next.getKey()).startTraversal(
					(Collection)next.getValue(),
					sub);
				
				next.setValue(sub);
			}
			
			delegateIterator = delegates.keySet().iterator();
		}

		/* (non-Javadoc)
		 * Redefines/Implements/Extends the inherited method.
		 */
		public boolean hasNext() {
			if ((current == null) && (delegateIterator.hasNext())) {
				current = (ITraversalStrategy)delegateIterator.next();
			}
			
			if (current == null) {
				return false;
			}
			
			if (!current.hasNext()) {
				((IProgressMonitor)delegates.get(current)).done();
				current = null;
				
				return hasNext();  // get the next delegate and try it
			}
			
			return true;
		}

		/* (non-Javadoc)
		 * Redefines/Implements/Extends the inherited method.
		 */
		public EObject next() {
			if (!hasNext()) {
				throw new NoSuchElementException();
			}
			
			return current.next();
		}
		
		/* (non-Javadoc)
		 * Redefines/Implements/Extends the inherited method.
		 */
		public boolean isClientContextChanged() {
			if (current != null) {
				return current.isClientContextChanged();
			}
			
			return false;
		}

		/* (non-Javadoc)
		 * Redefines/Implements/Extends the inherited method.
		 */
		public void elementValidated(EObject element, IStatus status) {
			current.elementValidated(element, status);
		}
		
		private Map initDelegates(Collection traversalRoots) {
			Map result = new java.util.HashMap();
			
			for (Iterator iter = traversalRoots.iterator(); iter.hasNext();) {
				EObject next = (EObject)iter.next();
				
				ITraversalStrategy delegate = TraversalStrategyManager
					.getInstance().getTraversalStrategy(next);
				
				Collection delegateRoots = (Collection)result.get(delegate);
				if (delegateRoots == null) {
					delegateRoots = new java.util.LinkedList();
					result.put(delegate, delegateRoots);
				}
				
				delegateRoots.add(next);
			}
			
			return result;
		}
	}
}
