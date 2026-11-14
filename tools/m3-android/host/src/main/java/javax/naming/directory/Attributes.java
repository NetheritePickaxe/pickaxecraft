package javax.naming.directory;

/** 仅宿主侧的最小占位接口，属于 M3 证据工具，不进入客户端产物。背景见 {@code com.sun.jndi.dns.DnsContextFactory}。 */
public interface Attributes {

    Attribute get(String attributeId);
}
