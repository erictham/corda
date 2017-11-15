package net.corda.node.services.identity

import net.corda.core.crypto.Crypto
import net.corda.core.crypto.generateKeyPair
import net.corda.core.identity.AnonymousParty
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.identity.PartyAndCertificate
import net.corda.core.internal.toX509CertHolder
import net.corda.core.node.services.UnknownAnonymousPartyException
import net.corda.core.internal.cert
import net.corda.node.services.identity.InMemoryIdentityService
import net.corda.node.utilities.CertificateAndKeyPair
import net.corda.node.utilities.CertificateType
import net.corda.node.utilities.X509Utilities
import net.corda.testing.*
import org.junit.Test
import java.security.cert.CertificateFactory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Tests for the in memory identity service.
 */
class InMemoryIdentityServiceTests {
    @Test
    fun `get all identities`() {
        val service = InMemoryIdentityService(trustRoot = DEV_TRUST_ROOT)
        // Nothing registered, so empty set
        assertNull(service.getAllIdentities().firstOrNull())

        service.verifyAndRegisterIdentity(ALICE_IDENTITY)
        var expected = setOf(ALICE)
        var actual = service.getAllIdentities().map { it.party }.toHashSet()
        assertEquals(expected, actual)

        // Add a second party and check we get both back
        service.verifyAndRegisterIdentity(BOB_IDENTITY)
        expected = setOf(ALICE, BOB)
        actual = service.getAllIdentities().map { it.party }.toHashSet()
        assertEquals(expected, actual)
    }

    @Test
    fun `get identity by key`() {
        val service = InMemoryIdentityService(trustRoot = DEV_TRUST_ROOT)
        assertNull(service.partyFromKey(ALICE_PUBKEY))
        service.verifyAndRegisterIdentity(ALICE_IDENTITY)
        assertEquals(ALICE, service.partyFromKey(ALICE_PUBKEY))
        assertNull(service.partyFromKey(BOB_PUBKEY))
    }

    @Test
    fun `get identity by name with no registered identities`() {
        val service = InMemoryIdentityService(trustRoot = DEV_TRUST_ROOT)
        assertNull(service.wellKnownPartyFromX500Name(ALICE.name))
    }

    @Test
    fun `get identity by substring match`() {
        val service = InMemoryIdentityService(trustRoot = DEV_TRUST_ROOT)
        service.verifyAndRegisterIdentity(ALICE_IDENTITY)
        service.verifyAndRegisterIdentity(BOB_IDENTITY)
        val alicente = getTestPartyAndCertificate(CordaX500Name(organisation = "Alicente Worldwide", locality = "London", country = "GB"), generateKeyPair().public)
        service.verifyAndRegisterIdentity(alicente)
        assertEquals(setOf(ALICE, alicente.party), service.partiesFromName("Alice", false))
        assertEquals(setOf(ALICE), service.partiesFromName("Alice Corp", true))
        assertEquals(setOf(BOB), service.partiesFromName("Bob Plc", true))
    }

    @Test
    fun `get identity by name`() {
        val service = InMemoryIdentityService(trustRoot = DEV_TRUST_ROOT)
        val identities = listOf("Org A", "Org B", "Org C")
                .map { getTestPartyAndCertificate(CordaX500Name(organisation = it, locality = "London", country = "GB"), generateKeyPair().public) }
        assertNull(service.wellKnownPartyFromX500Name(identities.first().name))
        identities.forEach { service.verifyAndRegisterIdentity(it) }
        identities.forEach { assertEquals(it.party, service.wellKnownPartyFromX500Name(it.name)) }
    }

    /**
     * Generate a certificate path from a root CA, down to a transaction key, store and verify the association.
     */
    @Test
    fun `assert unknown anonymous key is unrecognised`() {
        withTestSerialization {
            val rootKey = Crypto.generateKeyPair(X509Utilities.DEFAULT_TLS_SIGNATURE_SCHEME)
            val rootCert = X509Utilities.createSelfSignedCACertificate(ALICE.name, rootKey)
            val txKey = Crypto.generateKeyPair(X509Utilities.DEFAULT_TLS_SIGNATURE_SCHEME)
            val service = InMemoryIdentityService(trustRoot = DEV_TRUST_ROOT)
            // TODO: Generate certificate with an EdDSA key rather than ECDSA
            val identity = Party(rootCert.cert)
            val txIdentity = AnonymousParty(txKey.public)

            assertFailsWith<UnknownAnonymousPartyException> {
                service.assertOwnership(identity, txIdentity)
            }
        }
    }

    /**
     * Generate a pair of certificate paths from a root CA, down to a transaction key, store and verify the associations.
     * Also checks that incorrect associations are rejected.
     */
    @Test
    fun `get anonymous identity by key`() {
        val (alice, aliceTxIdentity) = createParty(ALICE.name, DEV_CA)
        val (_, bobTxIdentity) = createParty(ALICE.name, DEV_CA)

        // Now we have identities, construct the service and let it know about both
        val service = InMemoryIdentityService(setOf(alice), emptySet(), DEV_TRUST_ROOT)
        service.verifyAndRegisterIdentity(aliceTxIdentity)

        var actual = service.certificateFromKey(aliceTxIdentity.party.owningKey)
        assertEquals(aliceTxIdentity, actual!!)

        assertNull(service.certificateFromKey(bobTxIdentity.party.owningKey))
        service.verifyAndRegisterIdentity(bobTxIdentity)
        actual = service.certificateFromKey(bobTxIdentity.party.owningKey)
        assertEquals(bobTxIdentity, actual!!)
    }

    /**
     * Generate a pair of certificate paths from a root CA, down to a transaction key, store and verify the associations.
     * Also checks that incorrect associations are rejected.
     */
    @Test
    fun `assert ownership`() {
        withTestSerialization {
            val (alice, anonymousAlice) = createParty(ALICE.name, DEV_CA)
            val (bob, anonymousBob) = createParty(BOB.name, DEV_CA)

            // Now we have identities, construct the service and let it know about both
            val service = InMemoryIdentityService(setOf(alice, bob), emptySet(), DEV_TRUST_ROOT)

            service.verifyAndRegisterIdentity(anonymousAlice)
            service.verifyAndRegisterIdentity(anonymousBob)

            // Verify that paths are verified
            service.assertOwnership(alice.party, anonymousAlice.party.anonymise())
            service.assertOwnership(bob.party, anonymousBob.party.anonymise())
            assertFailsWith<IllegalArgumentException> {
                service.assertOwnership(alice.party, anonymousBob.party.anonymise())
            }
            assertFailsWith<IllegalArgumentException> {
                service.assertOwnership(bob.party, anonymousAlice.party.anonymise())
            }

            assertFailsWith<IllegalArgumentException> {
                val owningKey = Crypto.decodePublicKey(DEV_CA.certificate.subjectPublicKeyInfo.encoded)
                val subject = CordaX500Name.build(DEV_CA.certificate.cert.subjectX500Principal)
                service.assertOwnership(Party(subject, owningKey), anonymousAlice.party.anonymise())
            }
        }
    }

    private fun createParty(x500Name: CordaX500Name, ca: CertificateAndKeyPair): Pair<PartyAndCertificate, PartyAndCertificate> {
        val certFactory = CertificateFactory.getInstance("X509")
        val issuerKeyPair = generateKeyPair()
        val issuer = getTestPartyAndCertificate(x500Name, issuerKeyPair.public, ca)
        val txKey = Crypto.generateKeyPair()
        val txCert = X509Utilities.createCertificate(CertificateType.IDENTITY, issuer.certificate.toX509CertHolder(), issuerKeyPair, x500Name, txKey.public)
        val txCertPath = certFactory.generateCertPath(listOf(txCert.cert) + issuer.certPath.certificates)
        return Pair(issuer, PartyAndCertificate(txCertPath))
    }

    /**
     * Ensure if we feed in a full identity, we get the same identity back.
     */
    @Test
    fun `deanonymising a well known identity should return the identity`() {
        val service = InMemoryIdentityService(trustRoot = DEV_TRUST_ROOT)
        val expected = ALICE
        service.verifyAndRegisterIdentity(ALICE_IDENTITY)
        val actual = service.wellKnownPartyFromAnonymous(expected)
        assertEquals(expected, actual)
    }

    /**
     * Ensure we don't blindly trust what an anonymous identity claims to be.
     */
    @Test
    fun `deanonymising a false well known identity should return null`() {
        val service = InMemoryIdentityService(trustRoot = DEV_TRUST_ROOT)
        val notAlice = Party(ALICE.name, generateKeyPair().public)
        service.verifyAndRegisterIdentity(ALICE_IDENTITY)
        val actual = service.wellKnownPartyFromAnonymous(notAlice)
        assertNull(actual)
    }
}
