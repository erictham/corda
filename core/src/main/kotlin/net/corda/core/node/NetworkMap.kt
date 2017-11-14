package net.corda.core.node

import net.corda.core.crypto.DigitalSignature
import net.corda.core.crypto.SecureHash
import net.corda.core.crypto.SignedData
import net.corda.core.identity.Party
import net.corda.core.serialization.CordaSerializable
import net.corda.core.serialization.serialize
import java.security.cert.CertPath
import java.time.Duration
import java.time.Instant

/**
 * Data class containing hash of [NetworkParameters] and network participant's [NodeInfo] hashes.
 */
@CordaSerializable
data class NetworkMap(val nodeInfoHashes: List<SecureHash>, val networkParameterHash: SecureHash)

/**
 * @property minimumPlatformVersion
 * @property notaries
 * @property eventHorizon
 * @property maxMessageSize Maximum P2P message sent over the wire in bytes.
 * @property maxTransactionSize Maximum permitted transaction size in bytes.
 * @property modifiedTime
 * @property epoch Version number of the network parameters. Starting from 1, this will always increment on each new set
 * of parameters.
 */
// TODO Wire up the parameters
@CordaSerializable
data class NetworkParameters(
        val minimumPlatformVersion: Int,
        val notaries: List<NotaryInfo>,
        val eventHorizon: Duration,
        val maxMessageSize: Int,
        val maxTransactionSize: Int,
        val modifiedTime: Instant,
        val epoch: Int
) {
    init {
        require(minimumPlatformVersion > 0) { "minimumPlatformVersion must be at least 1" }
        require(notaries.distinctBy { it.identity } == notaries) { "Duplicate notary identities" }
        require(epoch > 0) { "epoch must be at least 1" }
    }
}

/**
 *
 */
@CordaSerializable
data class NotaryInfo(val identity: Party, val validating: Boolean)

class SignedNetworkMap(networkMap: NetworkMap, val signatureAndCert: DigitalSignatureWithCertPath)
    : SignedData<NetworkMap>(networkMap.serialize(), signatureAndCert)

class DigitalSignatureWithCertPath(val certPath: CertPath, signatureBytes: ByteArray)
    : DigitalSignature.WithKey(certPath.certificates.first().publicKey, signatureBytes)
