package javax.naming.directory;

/**
 * 仅宿主侧的最小占位接口，属于 M3 证据工具，不进入客户端产物。
 *
 * <p>只保留 mcprotocollib 的 {@code NettyHelper.resolveSrv} 用到的成员签名：
 * {@code DIR_CONTEXT.getAttributes(name, new String[]{"SRV"}).get("srv")}。
 * 背景见 {@code com.sun.jndi.dns.DnsContextFactory}。
 */
public interface Attribute {

    /** SRV 记录文本（{@code priority weight port target}），这里从不返回内容。 */
    Object get();
}
