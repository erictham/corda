package net.corda.node.services.network

import net.corda.core.crypto.Crypto
import net.corda.core.crypto.SecureHash
import net.corda.core.crypto.SignedData
import net.corda.core.crypto.sha256
import net.corda.core.identity.CordaX500Name
import net.corda.core.internal.cert
import net.corda.core.node.*
import net.corda.core.serialization.deserialize
import net.corda.core.serialization.serialize
import net.corda.core.utilities.hours
import net.corda.core.utilities.seconds
import net.corda.node.services.network.TestNodeInfoFactory.createNodeInfo
import net.corda.node.utilities.CertificateType
import net.corda.node.utilities.X509Utilities
import net.corda.testing.SerializationEnvironmentRule
import org.assertj.core.api.Assertions.assertThat
import org.bouncycastle.asn1.x500.X500Name
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.ServerConnector
import org.eclipse.jetty.server.handler.HandlerCollection
import org.eclipse.jetty.servlet.ServletContextHandler
import org.eclipse.jetty.servlet.ServletHolder
import org.glassfish.jersey.server.ResourceConfig
import org.glassfish.jersey.servlet.ServletContainer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.URL
import java.security.cert.CertificateFactory
import java.time.Instant
import javax.ws.rs.*
import javax.ws.rs.core.MediaType
import javax.ws.rs.core.Response
import javax.ws.rs.core.Response.ok
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class NetworkMapClientTest {
    @Rule
    @JvmField
    val testSerialization = SerializationEnvironmentRule(true)
    private lateinit var server: Server
    private lateinit var networkMapClient: NetworkMapClient

    @Before
    fun setUp() {
        server = Server(InetSocketAddress("localhost", 0)).apply {
            handler = HandlerCollection().apply {
                addHandler(ServletContextHandler().apply {
                    contextPath = "/"
                    val resourceConfig = ResourceConfig().apply {
                        // Add your API provider classes (annotated for JAX-RS) here
                        register(MockNetworkMapServer())
                    }
                    val jerseyServlet = ServletHolder(ServletContainer(resourceConfig)).apply { initOrder = 0 }// Initialise at server start
                    addServlet(jerseyServlet, "/*")
                })
            }
        }
        server.start()

        while (!server.isStarted) {
            Thread.sleep(100)
        }

        val hostAndPort = server.connectors.mapNotNull { it as? ServerConnector }.first()
        networkMapClient = NetworkMapClient(URL("http://${hostAndPort.host}:${hostAndPort.localPort}"), MockNetworkMapServer.rootCACert.cert)
    }

    @After
    fun tearDown() {
        server.stop()
    }

    @Test
    fun `registered node is added to the network map`() {
        // Create node info.
        val signedNodeInfo = createNodeInfo("Test1")
        val nodeInfo = signedNodeInfo.verified()

        networkMapClient.publish(signedNodeInfo)

        val nodeInfoHash = nodeInfo.serialize().sha256()

        assertThat(networkMapClient.getNetworkMap().networkMap.nodeInfoHashes).containsExactly(nodeInfoHash)
        assertEquals(nodeInfo, networkMapClient.getNodeInfo(nodeInfoHash))

        val signedNodeInfo2 = createNodeInfo("Test2")
        val nodeInfo2 = signedNodeInfo2.verified()
        networkMapClient.publish(signedNodeInfo2)

        val nodeInfoHash2 = nodeInfo2.serialize().sha256()
        assertThat(networkMapClient.getNetworkMap().networkMap.nodeInfoHashes).containsExactly(nodeInfoHash, nodeInfoHash2)
        assertEquals(100000.seconds, networkMapClient.getNetworkMap().cacheMaxAge)
        assertEquals(nodeInfo2, networkMapClient.getNodeInfo(nodeInfoHash2))
    }


    @Test
    fun `download NetworkParameter correctly`() {
        // The test server returns same network parameter for any hash.
        val networkParameter = networkMapClient.getNetworkParameter(SecureHash.randomSHA256())
        assertNotNull(networkParameter)
        assertEquals(MockNetworkMapServer.stubNetworkParameter, networkParameter)
    }

    @Test
    fun `get hostname string from http response correctly`() {
       assertEquals("test.host.name", networkMapClient.myPublicHostname())
    }
}

@Path("network-map")
// This is a stub implementation of the network map rest API.
internal class MockNetworkMapServer {
    companion object {
        val stubNetworkParameter = NetworkParameters(1, emptyList(), 1.hours, 10, 10, Instant.now(), 10)
        private val rootCAKey = Crypto.generateKeyPair(X509Utilities.DEFAULT_TLS_SIGNATURE_SCHEME)
        val rootCACert = X509Utilities.createSelfSignedCACertificate(CordaX500Name(commonName = "Corda Node Root CA", organisation = "R3 LTD", locality = "London", country = "GB"), rootCAKey)
    }

    private val intermediateCAKey = Crypto.generateKeyPair(X509Utilities.DEFAULT_TLS_SIGNATURE_SCHEME)
    private val intermediateCACert = X509Utilities.createCertificate(CertificateType.INTERMEDIATE_CA, rootCACert, rootCAKey, X500Name("CN=Corda Node Intermediate CA,L=London"), intermediateCAKey.public)

    private val nodeInfoMap = mutableMapOf<SecureHash, SignedData<NodeInfo>>()
    @POST
    @Path("publish")
    @Consumes(MediaType.APPLICATION_OCTET_STREAM)
    fun publishNodeInfo(input: InputStream): Response {
        val registrationData = input.readBytes().deserialize<SignedData<NodeInfo>>()
        val nodeInfo = registrationData.verified()
        val nodeInfoHash = nodeInfo.serialize().sha256()
        nodeInfoMap.put(nodeInfoHash, registrationData)
        return ok().build()
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    fun getNetworkMap(): Response {
        val networkMap = NetworkMap(nodeInfoMap.keys.map { it }, SecureHash.randomSHA256())
        val serializedNetworkMap = networkMap.serialize()
        val signature = Crypto.doSign(intermediateCAKey.private, serializedNetworkMap.bytes)
        val certPath = CertificateFactory.getInstance("X509").generateCertPath(listOf(intermediateCACert.cert, rootCACert.cert))
        val signedNetworkMap = SignedNetworkMap(networkMap, DigitalSignatureWithCertPath(certPath, signature))
        return Response.ok(signedNetworkMap.serialize().bytes).header("Cache-Control", "max-age=100000").build()
    }

    @GET
    @Path("node-info/{var}")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    fun getNodeInfo(@PathParam("var") nodeInfoHash: String): Response {
        val signedNodeInfo = nodeInfoMap[SecureHash.parse(nodeInfoHash)]
        return if (signedNodeInfo != null) {
            Response.ok(signedNodeInfo.serialize().bytes)
        } else {
            Response.status(Response.Status.NOT_FOUND)
        }.build()
    }

    @GET
    @Path("network-parameter/{var}")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    fun getNetworkParameter(@PathParam("var") networkParameterHash: String): Response {
        return Response.ok(stubNetworkParameter.serialize().bytes).build()
    }

    @GET
    @Path("my-hostname")
    fun getHostName(): Response {
        return Response.ok("test.host.name").build()
    }
}
