package app.shura.translator.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * The cleartext policy the app ships with.
 *
 * Since targetSdk 34, Android blocks cleartext HTTP by default, so the default
 * server address (`http://127.0.0.1:8787`) was refused on every device on API 28
 * and up with "CLEARTEXT communication to 127.0.0.1 not permitted by network
 * security policy". That is a framework-level policy, so nothing in the JVM test
 * suite could see it: the unit tests exercise `HttpURLConnection`, not the Android
 * network stack.
 *
 * This test is the closest a hermetic test can get. It asserts the policy file says
 * what it must, that the manifest actually applies it — without that attribute the
 * XML is inert and the app is broken exactly as before — and that the exception is
 * loopback only, so it cannot quietly grow into "cleartext anywhere".
 */
public class NetworkSecurityPolicyTest {

    private static final String CONFIG_PATH = "src/main/res/xml/network_security_config.xml";
    private static final String MANIFEST_PATH = "src/main/AndroidManifest.xml";

    @Test
    public void theManifestAppliesThePolicy() throws Exception {
        String manifest = read(locate(MANIFEST_PATH));
        assertTrue(
                "AndroidManifest.xml must set android:networkSecurityConfig, or the policy file is inert: "
                        + manifest,
                manifest.contains("android:networkSecurityConfig=\"@xml/network_security_config\""));
        assertTrue("the manifest must not opt the whole app into cleartext", !manifest.contains("usesCleartextTraffic=\"true\""));
    }

    @Test
    public void cleartextIsRefusedEverywhereElse() throws Exception {
        Element base = firstChild(parse(locate(CONFIG_PATH)), "base-config");
        assertEquals("cleartext must be off by default; only the loopback exception may allow it",
                "false", base.getAttribute("cleartextTrafficPermitted"));
    }

    @Test
    public void loopbackIsTheOnlyCleartextException() throws Exception {
        assertEquals(
                "a wider cleartext exception would put translation traffic on the wire in plaintext",
                new LinkedHashSet<>(Arrays.asList("127.0.0.1", "localhost", "10.0.2.2")),
                cleartextDomains());
    }

    @Test
    public void theDefaultServerAddressIsCoveredByTheException() throws Exception {
        String host = URI.create(Prefs.DEFAULT_BASE_URL).getHost();
        assertTrue(
                "the default address " + Prefs.DEFAULT_BASE_URL + " would be blocked as cleartext",
                cleartextDomains().contains(host));
    }

    @Test
    public void noExceptionCoversSubdomains() throws Exception {
        Document config = parse(locate(CONFIG_PATH));
        NodeList domains = config.getElementsByTagName("domain");
        assertTrue("expected the loopback domains to be listed", domains.getLength() > 0);
        for (int i = 0; i < domains.getLength(); i++) {
            Element domain = (Element) domains.item(i);
            assertEquals("a subdomain wildcard would widen the exception beyond loopback",
                    "false", domain.getAttribute("includeSubdomains"));
        }
    }

    /** Hosts on which cleartext is permitted, in document order. */
    private static Set<String> cleartextDomains() throws Exception {
        List<String> hosts = new ArrayList<>();
        NodeList configs = parse(locate(CONFIG_PATH)).getElementsByTagName("domain-config");
        for (int i = 0; i < configs.getLength(); i++) {
            Element config = (Element) configs.item(i);
            if (!"true".equals(config.getAttribute("cleartextTrafficPermitted"))) {
                continue;
            }
            NodeList domains = config.getElementsByTagName("domain");
            for (int j = 0; j < domains.getLength(); j++) {
                Node node = domains.item(j).getFirstChild();
                if (node != null) {
                    hosts.add(node.getNodeValue().trim());
                }
            }
        }
        return new LinkedHashSet<>(hosts);
    }

    private static Element firstChild(Document document, String tag) {
        NodeList nodes = document.getElementsByTagName(tag);
        assertTrue("expected a <" + tag + "> in the network security config", nodes.getLength() > 0);
        return (Element) nodes.item(0);
    }

    private static Document parse(File file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        return factory.newDocumentBuilder().parse(file);
    }

    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    /**
     * Finds a file by walking up from the working directory, so the test works whether
     * Gradle runs it from the module directory or from the project root.
     */
    private static File locate(String relative) {
        File directory = new File(System.getProperty("user.dir")).getAbsoluteFile();
        for (int depth = 0; depth < 6 && directory != null; depth += 1) {
            File candidate = new File(directory, relative);
            if (candidate.isFile()) {
                return candidate;
            }
            directory = directory.getParentFile();
        }
        throw new IllegalStateException("could not find " + relative + " from " + System.getProperty("user.dir"));
    }
}
