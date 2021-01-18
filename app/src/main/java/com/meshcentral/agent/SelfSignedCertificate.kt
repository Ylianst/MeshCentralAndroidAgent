import android.util.Base64
import android.util.Log
import org.spongycastle.asn1.x500.X500Name
import org.spongycastle.cert.X509v3CertificateBuilder
import org.spongycastle.cert.jcajce.JcaX509CertificateConverter
import org.spongycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.spongycastle.jce.provider.BouncyCastleProvider
import org.spongycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.*
import java.math.BigInteger
import java.nio.charset.Charset
import java.security.*
import java.security.cert.CertificateEncodingException
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.*

class SelfSignedCertificate @JvmOverloads constructor(fqdn: String, random: SecureRandom, bits: Int, notBefore: Date? = DEFAULT_NOT_BEFORE, notAfter: Date? = DEFAULT_NOT_AFTER) {
    //private val certificate: File
    //private val privateKey: File
    private var cert: X509Certificate? = null
    private val key: PrivateKey
    var certb64: String? = null
    var keyb64: String? = null
    /**
     * Creates a new instance.
     *
     * @param notBefore Certificate is not valid before this time
     * @param notAfter  Certificate is not valid after this time
     */
    /**
     * Creates a new instance.
     */
    @JvmOverloads
    constructor(notBefore: Date? = DEFAULT_NOT_BEFORE, notAfter: Date? = DEFAULT_NOT_AFTER) : this("example.com", notBefore, notAfter) {
    }
    /**
     * Creates a new instance.
     *
     * @param fqdn      a fully qualified domain name
     * @param notBefore Certificate is not valid before this time
     * @param notAfter  Certificate is not valid after this time
     */
    /**
     * Creates a new instance.
     *
     * @param fqdn a fully qualified domain name
     */
    @JvmOverloads
    constructor(fqdn: String, notBefore: Date? = DEFAULT_NOT_BEFORE, notAfter: Date? = DEFAULT_NOT_AFTER) : this(fqdn, SecureRandom(), DEFAULT_KEY_LENGTH_BITS, notBefore, notAfter) {
        // Bypass entropy collection by using insecure random generator.
        // We just want to generate it without any delay because it's for testing purposes only.
    }

    /**
     * Returns the generated X.509 certificate.
     */
    fun cert(): X509Certificate? {
        return cert
    }

    /**
     * Returns the generated RSA private key.
     */
    fun key(): PrivateKey {
        return key
    }

    companion object {
        private val TAG = SelfSignedCertificate::class.java.simpleName

        /**
         * Current time minus 1 year, just in case software clock goes back due to time synchronization
         */
        private val DEFAULT_NOT_BEFORE = Date(System.currentTimeMillis() - 86400000L * 365)

        /**
         * The maximum possible value in X.509 specification: 9999-12-31 23:59:59
         */
        private val DEFAULT_NOT_AFTER = Date(253402300799000L)

        /**
         * FIPS 140-2 encryption requires the key length to be 2048 bits or greater.
         * Let's use that as a sane default but allow the default to be set dynamically
         * for those that need more stringent security requirements.
         */
        private const val DEFAULT_KEY_LENGTH_BITS = 2048

        /**
         * FQDN to use if none is specified.
         */
        private const val DEFAULT_FQDN = "android.agent.meshcentral.com"

        class CertAndKey (cert: X509Certificate, key: PrivateKey) {
            var cert: X509Certificate = cert
            var key: PrivateKey = key
        }

        /**
         * 7-bit ASCII, as known as ISO646-US or the Basic Latin block of the
         * Unicode character set
         */
        private val US_ASCII = Charset.forName("US-ASCII")
        private val provider: Provider = BouncyCastleProvider()
        @Throws(Exception::class)
        private fun generateCertificate(fqdn: String, keypair: KeyPair, random: SecureRandom, notBefore: Date?, notAfter: Date?): CertAndKey {
            val key = keypair.private

            // Prepare the information required for generating an X.509 certificate.
            val owner = X500Name("CN=$fqdn")
            val builder: X509v3CertificateBuilder = JcaX509v3CertificateBuilder(
                    owner, BigInteger(64, random), notBefore, notAfter, owner, keypair.public)
            val signer = JcaContentSignerBuilder("SHA256WithRSAEncryption").build(key)
            val certHolder = builder.build(signer)
            val cert = JcaX509CertificateConverter().setProvider(provider).getCertificate(certHolder)
            cert.verify(keypair.public)
            return CertAndKey(cert, key)
        }
    }
    /**
     * Creates a new instance.
     *
     * @param fqdn      a fully qualified domain name
     * @param random    the [java.security.SecureRandom] to use
     * @param bits      the number of bits of the generated private key
     * @param notBefore Certificate is not valid before this time
     * @param notAfter  Certificate is not valid after this time
     */
    /**
     * Creates a new instance.
     *
     * @param fqdn   a fully qualified domain name
     * @param random the [java.security.SecureRandom] to use
     * @param bits   the number of bits of the generated private key
     */
    init {
        // Generate an RSA key pair.
        val keypair: KeyPair
        keypair = try {
            val keyGen = KeyPairGenerator.getInstance("RSA")
            keyGen.initialize(bits, random)
            keyGen.generateKeyPair()
        } catch (e: NoSuchAlgorithmException) {
            // Should not reach here because every Java implementation must have RSA key pair generator.
            throw Error(e)
        }
        val certAndKey: CertAndKey
        certAndKey = try {
            // Try Bouncy Castle if the current JVM didn't have sun.security.x509.
            generateCertificate(fqdn, keypair, random, notBefore, notAfter)
        } catch (t2: Throwable) {
            Log.d(TAG, "Failed to generate a self-signed X.509 certificate using Bouncy Castle:", t2)
            throw CertificateException("No provider succeeded to generate a self-signed certificate. See debug log for the root cause.", t2)
        }
        cert = certAndKey.cert
        key = certAndKey.key
        keyb64 = Base64.encodeToString(certAndKey.key.encoded, Base64.DEFAULT)
        certb64 = Base64.encodeToString(certAndKey.cert.encoded, Base64.DEFAULT)
    }

}