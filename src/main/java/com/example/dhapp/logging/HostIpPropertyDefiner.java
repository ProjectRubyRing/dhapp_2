package com.example.dhapp.logging;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

import ch.qos.logback.core.PropertyDefinerBase;

/**
 * ログファイル名のプレフィックスに使う、このホストの IPv4 アドレスを返す Logback の PropertyDefiner。
 *
 * <p>logback-spring.xml の {@code <define>} から呼ばれ、区切り文字をハイフンに置き換えた形
 * （例: {@code 10.0.1.23} → {@code 10-0-1-23}）で返す。</p>
 *
 * <p>アドレスは次の順で決める。
 * <ol>
 *   <li>{@link InetAddress#getLocalHost()}（ホスト名から引いたアドレス）がループバック以外の IPv4 ならそれ</li>
 *   <li>稼働中・非ループバック・非仮想の NIC に付いた IPv4（サイトローカルを優先）</li>
 *   <li>どれも取れなければ {@code 127.0.0.1}</li>
 * </ol>
 * Logback の初期化中に呼ばれるため、例外は外へ投げずフォールバックする
 * （投げるとプロパティが未定義になり、ファイル名が {@code HOST_IP_IS_UNDEFINED} になる）。</p>
 */
public class HostIpPropertyDefiner extends PropertyDefinerBase {

    static final String FALLBACK_ADDRESS = "127.0.0.1";

    @Override
    public String getPropertyValue() {
        return toFileNameToken(resolveAddress());
    }

    /** IPv4 アドレスの区切り文字（.）をハイフンに置き換える。 */
    static String toFileNameToken(String address) {
        return address.replace('.', '-');
    }

    private String resolveAddress() {
        try {
            InetAddress local = InetAddress.getLocalHost();
            if (local instanceof Inet4Address && !local.isLoopbackAddress()) {
                return local.getHostAddress();
            }
        } catch (Exception e) {
            addWarn("InetAddress.getLocalHost() failed; falling back to network interfaces", e);
        }

        try {
            String siteLocal = null;
            String other = null;
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()) {
                    continue;
                }
                for (InetAddress address : Collections.list(nic.getInetAddresses())) {
                    if (!(address instanceof Inet4Address) || address.isLoopbackAddress()) {
                        continue;
                    }
                    if (address.isSiteLocalAddress()) {
                        if (siteLocal == null) {
                            siteLocal = address.getHostAddress();
                        }
                    } else if (other == null) {
                        other = address.getHostAddress();
                    }
                }
            }
            if (siteLocal != null) {
                return siteLocal;
            }
            if (other != null) {
                return other;
            }
        } catch (Exception e) {
            addWarn("Failed to enumerate network interfaces", e);
        }

        addWarn("No non-loopback IPv4 address found; using " + FALLBACK_ADDRESS);
        return FALLBACK_ADDRESS;
    }
}
