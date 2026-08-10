package com.example.dhapp.service;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.example.dhapp.dto.ElytronSslInfo;

/**
 * JBoss EAP / WildFly の elytron サブシステムに、jboss-cli で行った SSL 関連の登録が
 * 反映されているかをアプリ内から読み取る。
 *
 * <p>読み取りには <b>管理モデルの JMX ファサード</b>（ドメイン {@code jboss.as}）を使う。
 * EAP 既定の standalone.xml には jmx サブシステムが
 * {@code <expose-resolved-model/>} 付きで入っているため、
 * {@code ManagementFactory.getPlatformMBeanServer()} からそのまま参照できる。
 * つまり jboss-cli で読める値と同じものを、追加の依存無し（JDK 標準 API のみ）で取得できる。</p>
 *
 * <pre>
 * jboss-cli                                             JMX ObjectName
 * ------------------------------------------------------------------------------------
 * /subsystem=elytron:read-attribute(name=default-ssl-context)
 *                                                       jboss.as:subsystem=elytron
 * /subsystem=elytron/key-store=X:read-resource          jboss.as:subsystem=elytron,key-store=X
 * /subsystem=elytron/trust-manager=X:read-resource      jboss.as:subsystem=elytron,trust-manager=X
 * /subsystem=elytron/client-ssl-context=X:read-resource jboss.as:subsystem=elytron,client-ssl-context=X
 * </pre>
 *
 * <p>WildFly の JMX ファサードは属性名をキャメルケースに変換する（{@code key-store} →
 * {@code keyStore}）ため、属性の取り出しは記法差を無視して照合する（{@link #lookup}）。</p>
 *
 * <p>jmx サブシステムが無効、あるいは WildFly 以外で動いている場合は
 * {@code available=false} を返し、呼び出し側はそのチェックを UNKNOWN として扱う
 * （設定が間違っているとは限らないため NG にはしない）。</p>
 */
@Service
public class ElytronSslInspector {

    private static final Logger log = LoggerFactory.getLogger(ElytronSslInspector.class);

    /** 管理モデルの JMX ファサードのドメイン（解決済みモデル）。 */
    private static final String DOMAIN = "jboss.as";

    private static final String SUBSYSTEM = DOMAIN + ":subsystem=elytron";

    private static final String CHILD_KEY_STORE = "key-store";
    private static final String CHILD_TRUST_MANAGER = "trust-manager";
    private static final String CHILD_CLIENT_SSL_CONTEXT = "client-ssl-context";

    /** elytron サブシステムの、JVM 既定 SSLContext を指定する属性名。 */
    public static final String ATTR_DEFAULT_SSL_CONTEXT = "default-ssl-context";

    /** trust-manager が参照する key-store を指定する属性名。 */
    public static final String ATTR_KEY_STORE = "key-store";

    /** client-ssl-context が参照する trust-manager を指定する属性名。 */
    public static final String ATTR_TRUST_MANAGER = "trust-manager";

    /** key-store のファイルパス属性名。 */
    public static final String ATTR_PATH = "path";

    private final String expectedKeyStoreName;
    private final String expectedTrustManagerName;
    private final String expectedClientSslContextName;

    public ElytronSslInspector(
            @Value("${app.tls.elytron.key-store:cacertTrustStore}") String expectedKeyStoreName,
            @Value("${app.tls.elytron.trust-manager:cacertTrustManager}") String expectedTrustManagerName,
            @Value("${app.tls.elytron.client-ssl-context:cacertClientSslContext}")
            String expectedClientSslContextName) {
        this.expectedKeyStoreName = expectedKeyStoreName;
        this.expectedTrustManagerName = expectedTrustManagerName;
        this.expectedClientSslContextName = expectedClientSslContextName;
    }

    public String getExpectedKeyStoreName() {
        return expectedKeyStoreName;
    }

    public String getExpectedTrustManagerName() {
        return expectedTrustManagerName;
    }

    public String getExpectedClientSslContextName() {
        return expectedClientSslContextName;
    }

    /**
     * elytron サブシステムの登録状態を読み取る。
     * 例外は投げず、読めなかった場合は {@code available=false} と理由を返す。
     */
    public ElytronSslInfo inspect() {
        ElytronSslInfo info = new ElytronSslInfo();
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            ObjectName subsystem = new ObjectName(SUBSYSTEM);
            if (!server.isRegistered(subsystem)) {
                info.setAvailable(false);
                info.setUnavailableReason("MBean " + SUBSYSTEM + " が見つからない。"
                        + "JBoss EAP 上で動作していないか、jmx サブシステムが無効（standalone.xml の "
                        + "urn:jboss:domain:jmx / expose-resolved-model）の可能性がある。");
                return info;
            }

            info.setAvailable(true);
            info.setSource("JMX (" + SUBSYSTEM + ")");

            Map<String, String> subsystemAttributes = readAttributes(server, subsystem);
            info.setDefaultSslContext(lookup(subsystemAttributes, ATTR_DEFAULT_SSL_CONTEXT));

            info.setKeyStoreNames(childNames(server, CHILD_KEY_STORE));
            info.setTrustManagerNames(childNames(server, CHILD_TRUST_MANAGER));
            info.setClientSslContextNames(childNames(server, CHILD_CLIENT_SSL_CONTEXT));

            info.setKeyStoreAttributes(childAttributes(server, CHILD_KEY_STORE, expectedKeyStoreName));
            info.setTrustManagerAttributes(
                    childAttributes(server, CHILD_TRUST_MANAGER, expectedTrustManagerName));
            info.setClientSslContextAttributes(
                    childAttributes(server, CHILD_CLIENT_SSL_CONTEXT, expectedClientSslContextName));
        } catch (Exception e) {
            // 管理モデルが読めないこと自体は「設定が誤っている」ことを意味しないため、
            // 例外は握って UNKNOWN 扱いにできるようにする。
            info.setAvailable(false);
            info.setUnavailableReason(e.getClass().getSimpleName() + ": " + e.getMessage());
            log.warn("Failed to read the elytron subsystem via JMX.", e);
        }
        return info;
    }

    /**
     * ある種別の子リソース名を列挙する（{@code /subsystem=elytron/trust-manager=*} 相当）。
     */
    private List<String> childNames(MBeanServer server, String childType) {
        List<String> names = new ArrayList<>();
        try {
            Set<ObjectName> found = server.queryNames(
                    new ObjectName(SUBSYSTEM + "," + childType + "=*"), null);
            for (ObjectName objectName : found) {
                String name = objectName.getKeyProperty(childType);
                if (name != null) {
                    names.add(unquote(name));
                }
            }
            names.sort(String::compareTo);
        } catch (Exception e) {
            log.warn("Failed to enumerate elytron {} resources. reason={}", childType, e.toString());
        }
        return names;
    }

    /**
     * 指定した子リソースの全属性を読む（{@code :read-resource} 相当）。
     *
     * @return リソースが存在しない場合は null
     */
    private Map<String, String> childAttributes(MBeanServer server, String childType, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            ObjectName objectName = new ObjectName(SUBSYSTEM + "," + childType + "="
                    + ObjectName.quote(name));
            if (!server.isRegistered(objectName)) {
                // WildFly は名前に特殊文字が無ければクォート無しで登録するため、両方試す。
                objectName = new ObjectName(SUBSYSTEM + "," + childType + "=" + name);
                if (!server.isRegistered(objectName)) {
                    return null;
                }
            }
            return readAttributes(server, objectName);
        } catch (Exception e) {
            log.warn("Failed to read elytron {}={} . reason={}", childType, name, e.toString());
            return null;
        }
    }

    /** MBean の全属性を読み、値が定義されているものだけを文字列で返す。 */
    private Map<String, String> readAttributes(MBeanServer server, ObjectName objectName) throws Exception {
        MBeanAttributeInfo[] attributeInfos = server.getMBeanInfo(objectName).getAttributes();
        String[] names = new String[attributeInfos.length];
        for (int i = 0; i < attributeInfos.length; i++) {
            names[i] = attributeInfos[i].getName();
        }

        Map<String, String> values = new LinkedHashMap<>();
        AttributeList attributes = server.getAttributes(objectName, names);
        for (Attribute attribute : attributes.asList()) {
            Object value = attribute.getValue();
            if (value != null) {
                values.put(attribute.getName(), stringify(value));
            }
        }
        return values;
    }

    /**
     * 管理モデルの属性名（{@code default-ssl-context}）で値を引く。
     *
     * <p>JMX ファサードはキャメルケース（{@code defaultSslContext}）に変換するため、
     * 記号と大文字小文字を無視して照合する。</p>
     */
    public static String lookup(Map<String, String> attributes, String modelAttributeName) {
        if (attributes == null) {
            return null;
        }
        String normalized = normalize(modelAttributeName);
        for (Map.Entry<String, String> entry : attributes.entrySet()) {
            if (normalize(entry.getKey()).equals(normalized)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static String normalize(String name) {
        return name == null ? "" : name.replace("-", "").replace("_", "").toLowerCase(Locale.ROOT);
    }

    private static String stringify(Object value) {
        if (value instanceof Object[] array) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < array.length; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(array[i]);
            }
            return sb.append(']').toString();
        }
        return String.valueOf(value);
    }

    /** ObjectName のキー値がクォートされている場合に外す。 */
    private static String unquote(String value) {
        if (value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"') {
            return ObjectName.unquote(value);
        }
        return value;
    }
}
