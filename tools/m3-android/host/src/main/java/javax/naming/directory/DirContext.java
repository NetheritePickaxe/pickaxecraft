package javax.naming.directory;

import javax.naming.NamingException;

/**
 * 仅宿主侧的最小占位接口，属于 M3 证据工具，不进入客户端产物。
 *
 * <p>只保留 {@code NettyHelper.resolveSrv} 调用的那一个方法。真实 JNDI 的
 * {@code DirContext} 还继承 {@code javax.naming.Context}，这里刻意不重建那整套 API：
 * 宿主只需要让那次 SRV 查询以受控异常失败。背景见 {@code com.sun.jndi.dns.DnsContextFactory}。
 */
public interface DirContext {

    Attributes getAttributes(String name, String[] attrIds) throws NamingException;
}
