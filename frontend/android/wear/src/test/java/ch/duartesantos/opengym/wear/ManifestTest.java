package ch.duartesantos.opengym.wear;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public class ManifestTest {
    private static Set<String> permissions() throws Exception {
        // Gradle runs unit tests from the module directory (wear/).
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        NodeList uses = f.newDocumentBuilder().parse(new File("src/main/AndroidManifest.xml")).getElementsByTagName("uses-permission");
        Set<String> out = new HashSet<>();
        for (int i = 0; i < uses.getLength(); i++) {
            out.add(((Element) uses.item(i)).getAttributeNS("http://schemas.android.com/apk/res/android", "name"));
        }
        return out;
    }

    // RestAlarm: from Android 14 SCHEDULE_EXACT_ALARM is not granted at install, and the inexact
    // alarm it falls back to can come minutes late with the watch dozing. USE_EXACT_ALARM is.
    @Test
    public void asksForTheExactAlarmGrantedAtInstall() throws Exception {
        assertTrue(permissions().contains("android.permission.USE_EXACT_ALARM"));
    }
}
