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


package org.eclipse.emf.validation.internal.util.tests;

import junit.framework.TestSuite;

/**
 * Test suite encapsulating all of the JUnit tests in this package.
 * 
 * @author Christian W. Damus (cdamus)
 */
public final class AllTests extends TestSuite {
	/**
	 * Creates the test suite.
	 * 
	 * @return the test suite
	 */
	public AllTests() {
		super("Test for org.eclipse.emf.validation.internal.util package"); //$NON-NLS-1$

		addTestSuite(ConstraintFactoryTest.class);
		addTestSuite(DisabledConstraintTest.class);
		addTestSuite(FilteredCollectionTest.class);
		addTestSuite(JavaConstraintParserTest.class);
		addTestSuite(XmlConfigTest.class);
		addTestSuite(TextUtilsTest.class);
		addTestSuite(XmlExpressionSelectorTest.class);
	}
}
