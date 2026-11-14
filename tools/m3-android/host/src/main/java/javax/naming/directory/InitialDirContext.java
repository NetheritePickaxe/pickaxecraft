package javax.naming.directory;

import java.util.Hashtable;
import javax.naming.NamingException;

/**
 * 仅宿主侧的最小占位类，属于 M3 证据工具，不进入客户端产物。
 *
 * <p>mcprotocollib 的 {@code NettyHelper.<clinit>} 会 {@code new InitialDirContext(env)}。
 * Android 没有 JNDI，这里提供一个不连接的替代实现：构造成功，任何目录查询都以
 * {@link NamingException} 失败，于是 {@code resolveSrv} 回落到普通 DNS 解析。
 * 背景见 {@code com.sun.jndi.dns.DnsContextFactory}。
 */
public class InitialDirContext implements DirContext {

    private final Hashtable<?, ?> environment;

    public InitialDirContext(Hashtable<?, ?> environment) {
        this.environment = environment;
    }

    @Override
    public Attributes getAttributes(String name, String[] attrIds) throws NamingException {
        throw new NamingException("SRV lookup is unavailable on this host: " + environment.get("java.naming.provider.url"));
    }

    @Override
    public String toString() {
        return "InitialDirContext(shim, no JNDI on Android)";
    }
}
