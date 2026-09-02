package com.example.dhapp.service;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.dhapp.dto.DeploymentOverlayEntry;
import com.example.dhapp.dto.DeploymentOverlayInfo;

/**
 * JBoss EAP / WildFly の <b>deployment-overlay</b> の定義状態を、アプリ自身から読み取る。
 *
 * <p>deployment-overlay は「デプロイ済みの war の中のファイルを、war を再ビルドせずに
 * 差し替える」EAP の機能で、次のように設定する。</p>
 *
 * <pre>
 * # war 内のクラスパスリソースを、サーバ上の別ファイルで差し替える
 * deployment-overlay add --name=date-config-overlay \
 *     --content=WEB-INF/classes/jp/iwin/base/tango/date_config.properties=/opt/overlay/date_config.properties \
 *     --deployments=dhapp.war --redeploy-affected
 * </pre>
 *
 * <p>読み取りには {@link ElytronSslInspector} と同じく<b>管理モデルの JMX ファサード</b>
 * （ドメイン {@code jboss.as}）を使う。jboss-cli で読めるものと同じ値を、
 * 追加の依存無し（JDK 標準 API のみ）で取得できる。</p>
 *
 * <pre>
 * jboss-cli                                      JMX ObjectName
 * ---------------------------------------------------------------------------------
 * /deployment-overlay=X:read-resource            jboss.as:deployment-overlay=X
 * /deployment-overlay=X/content=P:read-resource  jboss.as:deployment-overlay=X,content=P
 * /deployment-overlay=X/deployment=D             jboss.as:deployment-overlay=X,deployment=D
 * </pre>
 *
 * <p>JBoss EAP 上で動いていない（ローカルの {@code java -jar} 実行など）場合や jmx サブシステムが
 * 無効な場合は {@code managementModelAvailable=false} を返すだけで、例外は投げない。
 * overlay の有無は「実際に読めた内容の指紋の変化」でも検知できるため
 * （{@link DateConfigService}）、ここが読めなくても機能全体は成立する。</p>
 */
@Service
public class DeploymentOverlayInspector {

    private static final Logger log = LoggerFactory.getLogger(DeploymentOverlayInspector.class);

    /** 管理モデルの JMX ファサードのドメイン。 */
    private static final String DOMAIN = "jboss.as";

    private static final String KEY_DEPLOYMENT_OVERLAY = "deployment-overlay";
    private static final String KEY_CONTENT = "content";
    private static final String KEY_DEPLOYMENT = "deployment";

    /** ModuleClassLoader の toString から {@code deployment.<war 名>} を取り出す。 */
    private static final Pattern DEPLOYMENT_MODULE =
            Pattern.compile("deployment\\.([^\\s\"'\\]:]+\\.(?:war|ear|jar))");

    /**
     * deployment-overlay の定義状態を読み取る。
     *
     * @param resourcePathInDeployment 差し替え対象として注目するデプロイ内パス。
     *        例: {@code WEB-INF/classes/jp/iwin/base/tango/date_config.properties}
     */
    public DeploymentOverlayInfo inspect(String resourcePathInDeployment) {
        DeploymentOverlayInfo info = new DeploymentOverlayInfo();
        info.setDeploymentName(resolveDeploymentName());

        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();

            // overlay が 1 件も定義されていないと deployment-overlay=* の MBean も存在しない。
            // 「JBoss 上で動いているか」は subsystem=deployment-scanner ではなく
            // ドメイン jboss.as の MBean が 1 つでもあるかで判定する。
            if (server.queryNames(new ObjectName(DOMAIN + ":*"), null).isEmpty()) {
                info.setManagementModelAvailable(false);
                info.setUnavailableReason("ドメイン " + DOMAIN + " の MBean が 1 つも見つからない。"
                        + "JBoss EAP 上で動作していないか、jmx サブシステムが無効"
                        + "（standalone.xml の urn:jboss:domain:jmx / expose-resolved-model）"
                        + "の可能性がある。");
                return info;
            }
            info.setManagementModelAvailable(true);

            Map<String, DeploymentOverlayEntry> entries = readOverlays(server);
            List<String> names = new ArrayList<>(entries.keySet());
            names.sort(Comparator.naturalOrder());
            info.setOverlayNames(names);

            List<String> dateConfigOverlays = new ArrayList<>();
            boolean appliedToThisDeployment = false;
            for (DeploymentOverlayEntry entry : entries.values()) {
                entry.setAppliesToThisDeployment(
                        matchesThisDeployment(entry.getDeployments(), info.getDeploymentName()));
                entry.setOverridesDateConfig(
                        overridesPath(entry.getContentPaths(), resourcePathInDeployment));
                appliedToThisDeployment |= entry.isAppliesToThisDeployment();
                if (entry.isOverridesDateConfig()) {
                    dateConfigOverlays.add(entry.getName());
                }
            }
            List<DeploymentOverlayEntry> overlays = new ArrayList<>(entries.values());
            overlays.sort(Comparator.comparing(DeploymentOverlayEntry::getName,
                    Comparator.nullsLast(Comparator.naturalOrder())));
            info.setOverlays(overlays);
            info.setOverlayAppliedToThisDeployment(appliedToThisDeployment);
            info.setDateConfigOverlayNames(dateConfigOverlays);
            info.setDateConfigOverlayDefined(!dateConfigOverlays.isEmpty());
        } catch (Exception e) {
            // 管理モデルが読めないこと自体は異常ではないので、握って理由だけ残す。
            info.setManagementModelAvailable(false);
            info.setUnavailableReason(e.getClass().getSimpleName() + ": " + e.getMessage());
            log.warn("Failed to read deployment overlays via JMX.", e);
        }
        return info;
    }

    /**
     * {@code jboss.as:deployment-overlay=*} とその子リソースを一括で読み、overlay 名ごとにまとめる。
     *
     * <p>子リソース（content / deployment）は ObjectName のキープロパティとして現れるため、
     * ワイルドカード 1 回のクエリで全件拾ってから振り分ける。content のパスは
     * {@code /} を含むため WildFly 側でクォートされることがあり、その場合は復元する。</p>
     */
    private Map<String, DeploymentOverlayEntry> readOverlays(MBeanServer server) throws Exception {
        Map<String, DeploymentOverlayEntry> entries = new LinkedHashMap<>();

        Set<ObjectName> found = new java.util.LinkedHashSet<>();
        found.addAll(server.queryNames(new ObjectName(DOMAIN + ":" + KEY_DEPLOYMENT_OVERLAY + "=*"), null));
        found.addAll(server.queryNames(new ObjectName(DOMAIN + ":" + KEY_DEPLOYMENT_OVERLAY + "=*,*"), null));

        for (ObjectName objectName : found) {
            String overlayName = unquote(objectName.getKeyProperty(KEY_DEPLOYMENT_OVERLAY));
            if (overlayName == null) {
                continue;
            }
            DeploymentOverlayEntry entry = entries.computeIfAbsent(overlayName, name -> {
                DeploymentOverlayEntry created = new DeploymentOverlayEntry();
                created.setName(name);
                created.setContentPaths(new ArrayList<>());
                created.setContentAttributes(new LinkedHashMap<>());
                created.setDeployments(new ArrayList<>());
                return created;
            });

            String content = unquote(objectName.getKeyProperty(KEY_CONTENT));
            if (content != null) {
                entry.getContentPaths().add(content);
                // content-hash は overlay の中身が差し替わったかの判別材料になるので残す。
                for (Map.Entry<String, String> attribute : readAttributes(server, objectName).entrySet()) {
                    entry.getContentAttributes().put(content + "." + attribute.getKey(), attribute.getValue());
                }
                continue;
            }

            String deployment = unquote(objectName.getKeyProperty(KEY_DEPLOYMENT));
            if (deployment != null) {
                entry.getDeployments().add(deployment);
            }
        }

        for (DeploymentOverlayEntry entry : entries.values()) {
            entry.getContentPaths().sort(Comparator.naturalOrder());
            entry.getDeployments().sort(Comparator.naturalOrder());
        }
        return entries;
    }

    /**
     * 実行中のデプロイメント名（{@code dhapp.war} など）を推定する。
     *
     * <p>まず自身のクラスローダ（WildFly では {@code ModuleClassLoader for Module
     * "deployment.dhapp.war"}）から取り出し、取れない場合は
     * {@code jboss.as:deployment=*} に 1 件しか無ければそれを採用する。</p>
     */
    public String resolveDeploymentName() {
        ClassLoader classLoader = getClass().getClassLoader();
        if (classLoader != null) {
            Matcher matcher = DEPLOYMENT_MODULE.matcher(String.valueOf(classLoader));
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            Set<ObjectName> found =
                    server.queryNames(new ObjectName(DOMAIN + ":" + KEY_DEPLOYMENT + "=*"), null);
            Set<String> names = new TreeSet<>();
            for (ObjectName objectName : found) {
                String name = unquote(objectName.getKeyProperty(KEY_DEPLOYMENT));
                if (name != null) {
                    names.add(name);
                }
            }
            if (names.size() == 1) {
                return names.iterator().next();
            }
        } catch (Exception e) {
            log.debug("Failed to resolve the deployment name via JMX. reason={}", e.toString());
        }
        return null;
    }

    /**
     * overlay の {@code deployment=} 指定が、このデプロイメントに当たるか。
     *
     * <p>{@code --deployments=} は完全名のほか {@code *.war} のようなワイルドカードでも
     * 指定できる（deployment-overlay の runtime-name パターン）。</p>
     */
    private static boolean matchesThisDeployment(List<String> deployments, String deploymentName) {
        if (deployments == null || deployments.isEmpty()) {
            return false;
        }
        if (deploymentName == null) {
            // デプロイメント名が特定できない場合は「当たっているかもしれない」を false 側に倒し、
            // 一覧（overlays）を見て人が判断できるようにする。
            return false;
        }
        for (String deployment : deployments) {
            if (deployment == null) {
                continue;
            }
            if (deployment.equals(deploymentName)) {
                return true;
            }
            if (deployment.contains("*")) {
                String regex = Pattern.quote(deployment).replace("*", "\\E.*\\Q");
                if (deploymentName.matches(regex)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** overlay の content 指定が、注目しているデプロイ内パスを差し替えているか。 */
    private static boolean overridesPath(List<String> contentPaths, String resourcePathInDeployment) {
        if (contentPaths == null || resourcePathInDeployment == null) {
            return false;
        }
        String target = normalize(resourcePathInDeployment);
        for (String content : contentPaths) {
            if (content != null && normalize(content).equals(target)) {
                return true;
            }
        }
        return false;
    }

    /** 先頭スラッシュ・大文字小文字の揺れを吸収する。 */
    private static String normalize(String path) {
        String normalized = path.replace('\\', '/').trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    /** MBean の全属性を読み、値が定義されているものだけを文字列で返す。 */
    private Map<String, String> readAttributes(MBeanServer server, ObjectName objectName) {
        Map<String, String> values = new LinkedHashMap<>();
        try {
            MBeanAttributeInfo[] attributeInfos = server.getMBeanInfo(objectName).getAttributes();
            String[] names = new String[attributeInfos.length];
            for (int i = 0; i < attributeInfos.length; i++) {
                names[i] = attributeInfos[i].getName();
            }
            AttributeList attributes = server.getAttributes(objectName, names);
            for (Attribute attribute : attributes.asList()) {
                Object value = attribute.getValue();
                if (value != null) {
                    values.put(attribute.getName(), stringify(value));
                }
            }
        } catch (Exception e) {
            log.debug("Failed to read attributes of {}. reason={}", objectName, e.toString());
        }
        return values;
    }

    /** 配列・バイト列（content-hash）も読める形にする。 */
    private static String stringify(Object value) {
        if (value instanceof byte[] bytes) {
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        }
        if (value instanceof Object[] array) {
            StringBuilder sb = new StringBuilder();
            for (Object element : array) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(stringify(element));
            }
            return sb.toString();
        }
        return String.valueOf(value);
    }

    /** ObjectName のキープロパティがクォートされている場合に元へ戻す。 */
    private static String unquote(String value) {
        if (value == null) {
            return null;
        }
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            try {
                return ObjectName.unquote(value);
            } catch (IllegalArgumentException e) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }
}
