package net.corda.testing

import org.junit.After
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass

/**
 * Base class for all integration tests that require common setup and/or teardown.
 */
abstract class IntegrationTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun globalSetUp() {
        }

        @AfterClass
        @JvmStatic
        fun globalTearDown() {
        }
    }

    @Before
    open fun setUp() {
    }

    @After
    open fun tearDown() {
    }
}